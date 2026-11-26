package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.SdkmanIds;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolVersionsIds;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/**
 * Finds and reads project files: {@code jkit.toml} or {@code .jkit.toml}, or else
 * {@code .sdkmanrc}, {@code .tool-versions} (asdf, mise) and {@code .java-version}. The nearest directory with any of them is the project; files from
 * different directories are never merged. Entries jkit can't use are skipped with a warning
 * so a file written for a newer jkit, or for SDKMAN, still works; broken TOML is an error.
 * See docs/project-files.md.
 */
public final class ProjectFile {
    public static final String JKIT_TOML = "jkit.toml";
    public static final String HIDDEN_TOML = ".jkit.toml";
    public static final String SDKMANRC = ".sdkmanrc";
    public static final String TOOL_VERSIONS = ".tool-versions";
    public static final String JAVA_VERSION = ".java-version";
    /** A file to use instead of searching; set but empty turns project files off. */
    public static final String OVERRIDE = "JKIT_PROJECT_FILE";

    private ProjectFile() { }

    /**
     * The project for {@code start} (a directory), searching upwards; empty when there is none.
     * Below {@code $HOME} the search stops at {@code $HOME} (which is still checked); elsewhere,
     * such as a CI workspace, it goes up to the root.
     */
    public static Optional<ProjectSpec> find(Path start, Map<String, String> env) {
        String override = env.get(OVERRIDE);
        if (override != null) {
            if (override.isBlank()) {
                return Optional.empty();
            }
            Path file = start.resolve(override).toAbsolutePath().normalize();
            if (!Path.of(override).isAbsolute() && !Files.exists(file)) {
                return Optional.empty(); // a relative name (ci/jkit.toml) applies only where it exists
            }
            if (!Files.isRegularFile(file)) {
                throw new JkitException(OVERRIDE + " points to " + file + ", which is not a file",
                        "unset it to search for jkit.toml, or set it empty to turn project files off");
            }
            String why = Path.of(override).isAbsolute() ? null : untrusted(file);
            if (why != null) {
                // a relative name is found in every folder you visit: the same rules as a search
                throw new JkitException(OVERRIDE + " names " + file + ", but " + why,
                        "give an absolute path to use that file anyway");
            }
            return Optional.of(read(file));
        }
        List<Path> files = locate(start, env);
        return files.isEmpty() ? Optional.empty() : Optional.of(inDir(files.getFirst().getParent()).orElseThrow());
    }

    /**
     * The project files {@link #find} would read, without reading them: the {@code JKIT_PROJECT_FILE}
     * file, a {@code jkit.toml} (or {@code .jkit.toml}), or the other formats found together. Empty
     * when there is none. Cheap: only file checks, for the {@code cd} hook.
     */
    public static List<Path> locate(Path start, Map<String, String> env) {
        String override = env.get(OVERRIDE);
        if (override != null) {
            if (override.isBlank()) {
                return List.of();
            }
            Path file = start.resolve(override).toAbsolutePath().normalize();
            return Path.of(override).isAbsolute() || Files.exists(file) ? List.of(file) : List.of();
        }
        return search(start, env).files();
    }

    /**
     * Why a project file above {@code start} is being ignored, for status, sync and doctor to say so:
     * its folder belongs to someone else or every user can write to it (common on WSL's /mnt/c, SMB
     * shares and Docker bind mounts). Empty when nothing was skipped.
     */
    public static Optional<String> skipped(Path start, Map<String, String> env) {
        if (env.get(OVERRIDE) != null) {
            return Optional.empty();
        }
        return Optional.ofNullable(search(start, env).skipped());
    }

    private record Search(List<Path> files, String skipped) { }

    private static Search search(Path start, Map<String, String> env) {
        Path dir = start.toAbsolutePath().normalize();
        Path home = home(env);
        boolean belowHome = home != null && dir.startsWith(home);
        String unreadable = null;
        for (; dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && dir.getFileName().toString().indexOf('\uFFFD') >= 0) {
                // a name that isn't UTF-8 comes back changed, so jkit can't look inside that folder
                unreadable = unreadable != null ? unreadable : "project files in " + dir + " can't be read: "
                        + "its name isn't valid UTF-8";
                continue;
            }
            List<Path> found = filesIn(dir);
            String why = found.isEmpty() ? trustProblem(dir) : null;
            Path bad = null;
            for (Path file : found) { // .sdkmanrc, .tool-versions and .java-version are read together
                why = untrusted(file);
                if (why != null) {
                    bad = file;
                    break;
                }
            }
            if (why != null) {
                // like git's safe.directory: someone else's folder can't steer this shell
                return new Search(List.of(), bad == null ? null : "ignoring " + bad + ": " + why);
            }
            if (!found.isEmpty() || (belowHome && dir.equals(home))) {
                return new Search(found, found.isEmpty() ? unreadable : null);
            }
        }
        return new Search(List.of(), unreadable);
    }

    /** Why {@code file} must not apply, or null: its folder or the file itself isn't safe. */
    static String untrusted(Path file) {
        String why = trustProblem(file.getParent());
        if (why != null) {
            return why;
        }
        if (Files.isSymbolicLink(file)) {
            // a link may only point at a file in the same folder or below: not at /proc/self/environ,
            // ~/.ssh/id_rsa or anything else the warnings would then quote
            try {
                Path real = file.toRealPath();
                if (!real.startsWith(file.getParent().toRealPath()) || !Files.isRegularFile(real)) {
                    return "it is a link to a file outside its folder";
                }
            } catch (IOException | RuntimeException e) {
                return "it is a link jkit can't follow";
            }
        }
        if (!POSIX) {
            return null;
        }
        try {
            if (!Owners.mineOrRoot(file)) {
                return "it belongs to " + Owners.owner(file);
            }
            if (Owners.othersWritable(file)) {
                return "every user can write to it (chmod o-w to use it)";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "cannot check its owner: " + e.getMessage();
        }
    }

    /**
     * Whether project files in {@code dir} may apply: it belongs to this user or root, and others
     * can't write to it. A {@code /tmp/jkit.toml} from another user must not change versions, or print
     * text, in every shell that visits {@code /tmp/...}. Always true where there are no POSIX owners.
     */
    static boolean trusted(Path dir) {
        return trustProblem(dir) == null;
    }

    private static String trustProblem(Path dir) {
        if (!POSIX) {
            return null;
        }
        try {
            if (!Owners.mineOrRoot(dir)) {
                return "the folder belongs to " + Owners.owner(dir);
            }
            if (Owners.othersWritable(dir)) {
                return "every user can write to the folder";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "cannot check the folder's owner";
        }
    }

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,64}");
    private static final Pattern SAFE_VENDOR = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * Drops pins whose version or vendor isn't plain text: project files come with cloned repos and
     * their values are printed on every {@code cd}, so escape sequences must never get that far.
     */
    static ProjectSpec checked(ProjectSpec spec) {
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        List<String> warnings = new ArrayList<>(spec.warnings());
        spec.tools().forEach((tool, pin) -> {
            boolean versionOk = pin.version() == null || SAFE_VERSION.matcher(pin.version()).matches();
            boolean vendorOk = pin.vendor() == null || SAFE_VENDOR.matcher(pin.vendor().name()).matches();
            if (versionOk && vendorOk) {
                tools.put(tool, pin);
            } else {
                warnings.add(spec.files().getFirst() + ": " + tool + " pins a "
                        + (versionOk ? "vendor" : "version") + " with characters jkit doesn't accept, skipped");
            }
        });
        if (warnings.size() > MAX_WARNINGS) {
            // a file of thousands of unknown tables must not print thousands of lines on every cd
            int more = warnings.size() - MAX_WARNINGS;
            warnings.subList(MAX_WARNINGS, warnings.size()).clear();
            warnings.add(spec.files().getFirst() + ": " + more + " more warnings not shown");
        }
        return new ProjectSpec(spec.files(), tools, warnings);
    }

    static final int MAX_WARNINGS = 20;

    private static List<Path> filesIn(Path dir) {
        for (String name : List.of(JKIT_TOML, HIDDEN_TOML)) {
            if (Files.isRegularFile(dir.resolve(name))) {
                return List.of(dir.resolve(name));
            }
        }
        List<Path> found = new ArrayList<>();
        for (String name : List.of(SDKMANRC, TOOL_VERSIONS, JAVA_VERSION)) {
            if (Files.isRegularFile(dir.resolve(name))) {
                found.add(dir.resolve(name));
            }
        }
        return found;
    }

    /**
     * Identifies the project files and their contents' age, {@code path:mtime} joined by
     * {@code ;}, so the {@code cd} hook can tell nothing changed without reading them.
     */
    public static String key(List<Path> files) {
        List<String> parts = new ArrayList<>();
        for (Path f : files) {
            long mtime;
            try {
                mtime = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException e) {
                mtime = -1;
            }
            parts.add(f + ":" + mtime);
        }
        return String.join(";", parts);
    }

    private static Path home(Map<String, String> env) {
        Path home = Fs.path(env.getOrDefault("HOME", System.getProperty("user.home")));
        return home == null || !home.isAbsolute() ? null : home.normalize();
    }

    /**
     * {@code jkit.toml} (or {@code .jkit.toml}) alone; otherwise the other formats together, earlier
     * ones winning per tool: {@code .sdkmanrc}, {@code .tool-versions}, {@code .java-version}.
     */
    private static Optional<ProjectSpec> inDir(Path dir) {
        Path toml = dir.resolve(JKIT_TOML);
        Path hidden = dir.resolve(HIDDEN_TOML);
        if (Files.isRegularFile(toml)) {
            ProjectSpec spec = checked(readToml(toml));
            if (Files.isRegularFile(hidden)) {
                spec = withWarning(spec, hidden + ": ignored, " + JKIT_TOML + " in the same folder wins");
            }
            return Optional.of(spec);
        }
        if (Files.isRegularFile(hidden)) {
            return Optional.of(checked(readToml(hidden)));
        }
        List<ProjectSpec> found = new ArrayList<>();
        for (String name : List.of(SDKMANRC, TOOL_VERSIONS, JAVA_VERSION)) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                found.add(read(file));
            }
        }
        return found.isEmpty() ? Optional.empty() : Optional.of(merge(found));
    }

    /** Reads one file, choosing the format by its name ({@code jkit.toml} for anything else). */
    public static ProjectSpec read(Path file) {
        String name = file.getFileName().toString();
        return checked(switch (name) {
            case SDKMANRC -> readSdkmanrc(file);
            case TOOL_VERSIONS -> readToolVersions(file);
            case JAVA_VERSION -> readJavaVersion(file);
            default -> readToml(file);
        });
    }

    /** Earlier specs win where several pin the same tool. */
    private static ProjectSpec merge(List<ProjectSpec> specs) {
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        List<String> warnings = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (ProjectSpec spec : specs) {
            spec.tools().forEach(tools::putIfAbsent);
            warnings.addAll(spec.warnings());
            files.addAll(spec.files());
        }
        return new ProjectSpec(files, tools, warnings);
    }

    private static ProjectSpec withWarning(ProjectSpec spec, String warning) {
        List<String> warnings = new ArrayList<>(spec.warnings());
        warnings.add(warning);
        return new ProjectSpec(spec.files(), spec.tools(), warnings);
    }

    // jkit.toml

    static ProjectSpec readToml(Path file) {
        try {
            return readToml(file, text(file));
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
    }

    /** {@code text} read as the {@code jkit.toml} at {@code file}, which needn't exist (for checking an edit). */
    static ProjectSpec readToml(Path file, String text) {
        TomlParseResult toml = parseToml(file, text);
        if (toml.hasErrors()) {
            TomlParseError error = toml.errors().getFirst();
            throw new JkitException(at(file, error.position()) + ": invalid TOML: " + error.getMessage());
        }
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        Map<Tool, String> keyFor = new EnumMap<>(Tool.class);
        for (String key : toml.keySet()) {
            if (!key.equals("tools")) {
                warnings.add(at(file, toml.inputPositionOf(List.of(key))) + ": unknown key '" + shown(key) + "', skipped");
            }
        }
        if (toml.contains("tools") && !toml.isTable("tools")) {
            warnings.add(at(file, toml.inputPositionOf("tools")) + ": 'tools' should be a table ([tools]), skipped");
        } else if (toml.isTable("tools")) {
            TomlTable table = toml.getTable("tools");
            for (String key : table.keySet()) {
                String where = at(file, toml.inputPositionOf(List.of("tools", key)));
                Tool tool = Tool.find(key).orElse(null);
                if (tool == null) {
                    warnings.add(where + ": unknown tool '" + shown(key) + "', skipped (tools: jdk, maven, gradle, sbt, graalvm)");
                    continue;
                }
                if (keyFor.containsKey(tool)) {
                    throw new JkitException(where + ": '" + shown(key) + "' and '" + shown(keyFor.get(tool)) + "' both set " + tool,
                            "keep one of them");
                }
                keyFor.put(tool, key);
                PinnedTool pinned = tomlEntry(tool, key, table.get(List.of(key)), where, warnings);
                if (pinned != null) {
                    tools.put(tool, pinned);
                }
            }
        }
        return new ProjectSpec(List.of(file), tools, warnings);
    }

    /** {@code "21"} or {@code { version = "21", vendor = "zulu", fx = true }}; null when skipped. */
    private static PinnedTool tomlEntry(Tool tool, String key, Object value, String where, List<String> warnings) {
        if (value instanceof String text) {
            return pinned(tool, key, text, null, false, where, warnings);
        }
        if (!(value instanceof TomlTable table)) {
            warnings.add(where + ": " + shown(key) + " should be a version string or a table, skipped");
            return null;
        }
        String version = null;
        String vendor = null;
        boolean fx = false;
        for (String field : table.keySet()) {
            Object v = table.get(List.of(field));
            switch (field) {
                case "version" -> {
                    if (!(v instanceof String s)) {
                        warnings.add(where + ": " + shown(key) + ".version should be a string, skipped " + shown(key));
                        return null;
                    }
                    version = s;
                }
                case "vendor" -> {
                    if (!(v instanceof String s)) {
                        warnings.add(where + ": " + shown(key) + ".vendor should be a string, skipped " + shown(key));
                        return null;
                    }
                    vendor = s;
                }
                case "fx" -> {
                    if (!(v instanceof Boolean b)) {
                        warnings.add(where + ": " + shown(key) + ".fx should be true or false, skipped " + shown(key));
                        return null;
                    }
                    fx = b;
                }
                default -> warnings.add(where + ": unknown field '" + shown(field) + "' in " + shown(key) + ", ignored");
            }
        }
        return pinned(tool, key, version, vendor, fx, where, warnings);
    }

    private static PinnedTool pinned(Tool tool, String key, String versionText, String vendorText, boolean fx,
                                     String where, List<String> warnings) {
        String version = versionText == null ? null : versionText.trim();
        if (version != null && (version.isEmpty() || version.equalsIgnoreCase("latest"))) {
            version = null;
        }
        if (version != null && !Version.looksLikeVersion(version)) {
            warnings.add(where + ": " + shown(key) + " = '" + shown(versionText) + "' is not a version, skipped");
            return null;
        }
        Vendor vendor = null;
        if (vendorText != null && !vendorText.isBlank()) {
            if (!tool.hasVendors()) {
                warnings.add(where + ": " + tool + " has no vendors; vendor '" + shown(vendorText) + "' ignored");
            } else {
                vendor = Vendor.of(vendorText);
                if (tool == Tool.GRAALVM && !vendor.isGraalVmBased()) {
                    warnings.add(where + ": " + shown(vendor.toString()) + " is not GraalVM-based, skipped " + shown(key)
                            + " (graalvm vendors: " + Vendor.graalVmVendorList() + ")");
                    return null;
                }
            }
        }
        if (fx && !tool.hasVendors()) {
            warnings.add(where + ": " + tool + " has no JavaFX builds; fx ignored");
            fx = false;
        }
        return new PinnedTool(tool, version, vendor, fx);
    }

    // .sdkmanrc

    static ProjectSpec readSdkmanrc(Path file) {
        List<String> lines = lines(file);
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        TreeSet<String> unmanaged = new TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            if (line.isEmpty()) {
                continue;
            }
            String where = file + ":" + (i + 1);
            int eq = line.indexOf('=');
            if (eq <= 0) {
                warnings.add(where + ": expected candidate=version, skipped");
                continue;
            }
            String candidate = line.substring(0, eq).trim();
            String id = line.substring(eq + 1).trim();
            Tool tool = SdkmanIds.tool(candidate);
            if (tool == null) {
                unmanaged.add(candidate);
                continue;
            }
            try {
                tools.put(tool, SdkmanIds.parse(tool, id));
            } catch (IllegalArgumentException e) {
                warnings.add(where + ": " + shown(e.getMessage()) + ", skipped");
            }
        }
        if (!unmanaged.isEmpty()) {
            warnings.add(file + ": jkit doesn't manage " + shown(String.join(", ", unmanaged)) + ", skipped");
        }
        return new ProjectSpec(List.of(file), tools, warnings);
    }

    // .tool-versions (asdf, mise): "<name> <version> [fallback versions…]"; jkit uses the first

    static ProjectSpec readToolVersions(Path file) {
        List<String> lines = lines(file);
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        TreeSet<String> unmanaged = new TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            if (line.isEmpty()) {
                continue;
            }
            String where = file + ":" + (i + 1);
            String[] words = line.split("\\s+");
            if (words.length < 2) {
                warnings.add(where + ": expected '<tool> <version>', skipped");
                continue;
            }
            Tool tool = ToolVersionsIds.tool(words[0]);
            if (tool == null) {
                unmanaged.add(words[0]);
                continue;
            }
            try {
                tools.put(tool, ToolVersionsIds.parse(tool, words[1]));
            } catch (IllegalArgumentException e) {
                warnings.add(where + ": " + shown(e.getMessage()) + ", skipped");
            }
        }
        if (!unmanaged.isEmpty()) {
            warnings.add(file + ": jkit doesn't manage " + shown(String.join(", ", unmanaged)) + ", skipped");
        }
        return new ProjectSpec(List.of(file), tools, warnings);
    }

    // .java-version

    static ProjectSpec readJavaVersion(Path file) {
        List<String> lines = lines(file);
        for (int i = 0; i < lines.size(); i++) {
            String text = stripComment(lines.get(i));
            if (text.isEmpty()) {
                continue;
            }
            String version = legacyJavaVersion(text);
            if (!Version.looksLikeVersion(version)) {
                return new ProjectSpec(List.of(file), Map.of(), List.of(file + ":" + (i + 1) + ": '" + shown(text)
                        + "' is not a Java version (jkit reads only numbers such as 21 or 17.0.2), skipped"));
            }
            return new ProjectSpec(List.of(file), Map.of(Tool.JDK, new PinnedTool(Tool.JDK, version, null, false)),
                    List.of());
        }
        return new ProjectSpec(List.of(file), Map.of(), List.of(file + ": empty, skipped"));
    }

    /** {@code 1.8} → {@code 8}, {@code 1.8.0_392} → {@code 8.0_392}: the pre-9 numbering. */
    static String legacyJavaVersion(String text) {
        return Version.fromLegacyJava(text);
    }

    // helpers

    private static List<String> lines(Path file) {
        try {
            return text(file).lines().toList();
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
    }

    /**
     * The file as text: UTF-8 or UTF-16 by byte-order mark (PowerShell 5's {@code echo 21 > .java-version}
     * writes UTF-16), else UTF-8, else Latin-1. Never a decoding error.
     */
    public static String text(Path file) throws IOException {
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            // a named pipe would block every cd, waiting for a writer
            throw new JkitException(file + " is not a regular file");
        }
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_SIZE + 1);
        }
        if (bytes.length > MAX_SIZE) {
            // project files are a few lines; a huge one would make every cd slow, or run out of memory
            throw new JkitException(file + " is over " + MAX_SIZE / 1024 + " KiB, too big for a project or config file");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Text from a project file as a warning shows it: at most 40 characters, escaped. A file can be
     * a link to anything readable, and its content must not end up whole in a CI log.
     */
    static String shown(String text) {
        if (text == null) {
            return "";
        }
        String t = text.length() > 40 ? text.substring(0, 40) + "…" : text;
        return Visible.of(t);
    }

    /** The largest project or config file jkit reads. */
    static final int MAX_SIZE = 256 * 1024;

    /** TOML parsed; deep nesting ({@code a = [[[[...}) is an error, not a crash. */
    public static TomlParseResult parseToml(Path file, String text) {
        try {
            return Toml.parse(text);
        } catch (StackOverflowError e) {
            throw new JkitException(file + ": invalid TOML: nested too deeply");
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return (hash >= 0 ? line.substring(0, hash) : line).trim();
    }

    private static String at(Path file, TomlPosition position) {
        return position == null ? file.toString() : file + ":" + position.line();
    }

    private static JkitException cannotRead(Path file, IOException e) {
        return new JkitException("cannot read " + file + ": " + e.getMessage());
    }
}
