package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.SdkmanIds;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolVersionsIds;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        if (!Perms.posix()) {
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
     * Why project files in {@code dir} must not apply, or null when they may: it belongs to this
     * user or root, and others can't write to it. A {@code /tmp/jkit.toml} from another user must not
     * change versions, or print text, in every shell that visits {@code /tmp/...}. Always null where
     * there are no POSIX owners.
     */
    static String trustProblem(Path dir) {
        if (!Perms.posix()) {
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

    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,64}");
    private static final Pattern SAFE_VENDOR = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * Drops pins whose version or vendor isn't plain text: project files come with cloned repos and
     * their values are printed on every {@code cd}, so escape sequences must never get that far.
     */
    static ProjectSpec checked(ProjectSpec spec) {
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        List<String> warnings = new ArrayList<>(spec.warnings());
        Set<Tool> skipped = EnumSet.noneOf(Tool.class);
        skipped.addAll(spec.skipped());
        spec.tools().forEach((tool, pin) -> {
            boolean versionOk = pin.version() == null || SAFE_VERSION.matcher(pin.version()).matches();
            boolean vendorOk = pin.vendor() == null || SAFE_VENDOR.matcher(pin.vendor().name()).matches();
            if (versionOk && vendorOk) {
                tools.put(tool, pin);
            } else {
                skipped.add(tool);
                warnings.add(spec.origin(tool) + ": " + tool + " pins a "
                        + (versionOk ? "vendor" : "version") + " with characters jkit doesn't accept, skipped");
            }
        });
        if (warnings.size() > MAX_WARNINGS) {
            // a file of thousands of unknown tables must not print thousands of lines on every cd
            int more = warnings.size() - MAX_WARNINGS;
            warnings.subList(MAX_WARNINGS, warnings.size()).clear();
            warnings.add(spec.files().getFirst() + ": " + more + " more warnings not shown");
        }
        return new ProjectSpec(spec.files(), tools, warnings, skipped, spec.origins());
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

    /**
     * The first spec that mentions a tool decides it: its pin wins over later files, and so does a
     * pin it skips (a later file's pin doesn't stand in for one jkit couldn't read). A later file's
     * skipped pin doesn't count where an earlier file pinned the tool.
     */
    private static ProjectSpec merge(List<ProjectSpec> specs) {
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        Map<Tool, Path> origins = new EnumMap<>(Tool.class);
        Set<Tool> skipped = EnumSet.noneOf(Tool.class);
        List<String> warnings = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (ProjectSpec spec : specs) {
            spec.tools().forEach((tool, pin) -> {
                if (!skipped.contains(tool) && tools.putIfAbsent(tool, pin) == null) {
                    origins.put(tool, spec.origin(tool));
                }
            });
            for (Tool tool : spec.skipped()) {
                if (!tools.containsKey(tool)) {
                    skipped.add(tool);
                }
            }
            warnings.addAll(spec.warnings());
            files.addAll(spec.files());
        }
        return new ProjectSpec(files, tools, warnings, skipped, origins);
    }

    private static ProjectSpec withWarning(ProjectSpec spec, String warning) {
        List<String> warnings = new ArrayList<>(spec.warnings());
        warnings.add(warning);
        return new ProjectSpec(spec.files(), spec.tools(), warnings, spec.skipped(), spec.origins());
    }

    // jkit.toml

    static ProjectSpec readToml(Path file) {
        try {
            return readToml(file, TextFiles.text(file));
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
    }

    /** {@code text} read as the {@code jkit.toml} at {@code file}, which needn't exist (for checking an edit). */
    static ProjectSpec readToml(Path file, String text) {
        TomlParseResult toml = TextFiles.parseToml(file, text);
        if (toml.hasErrors()) {
            TomlParseError error = toml.errors().getFirst();
            throw new JkitException(at(file, error.position()) + ": invalid TOML: " + error.getMessage());
        }
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        Set<Tool> skipped = EnumSet.noneOf(Tool.class);
        Map<Tool, String> keyFor = new EnumMap<>(Tool.class);
        List<String> lines = text.lines().toList();
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
                TomlPosition position = toml.inputPositionOf(List.of("tools", key));
                String where = at(file, position);
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
                PinnedTool pinned = tomlEntry(tool, key, table.get(List.of(key)), file, position, lines, warnings);
                if (pinned != null) {
                    tools.put(tool, pinned);
                } else {
                    skipped.add(tool);
                }
            }
        }
        return new ProjectSpec(List.of(file), tools, warnings, skipped, Map.of());
    }

    /**
     * {@code "21"} or {@code { version = "21", vendor = "zulu", fx = true }}; null when skipped. A
     * whole number ({@code jdk = 21}) is read as its digits; a decimal ({@code maven = 3.10}) is
     * refused, as TOML reads it as the number 3.1 and the version would change.
     */
    private static PinnedTool tomlEntry(Tool tool, String key, Object value, Path file, TomlPosition position,
                                        List<String> lines, List<String> warnings) {
        String where = at(file, position);
        if (value instanceof String text) {
            return pinned(tool, key, text, null, false, where, warnings);
        }
        if (value instanceof Long number) {
            return pinned(tool, key, number.toString(), null, false, where, warnings);
        }
        if (value instanceof Double) {
            warnings.add(decimalWarning(where, key, key, lineAt(lines, position)));
            return null;
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
                    if (v instanceof Long number) {
                        version = number.toString();
                        continue;
                    }
                    if (v instanceof Double) {
                        // its own line: under [tools.maven], not the table's header
                        TomlPosition at = table.inputPositionOf(List.of(field));
                        warnings.add(decimalWarning(at == null ? where : at(file, at), key, "version",
                                lineAt(lines, at)));
                        return null;
                    }
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

    /**
     * Why a version written as a decimal is skipped, quoting it as written on its line when it can
     * be found there: TOML has already turned {@code 3.10} into 3.1.
     */
    private static String decimalWarning(String where, String key, String field, String line) {
        Matcher m = Pattern.compile("(?:^|[\\s{,\"'])" + Pattern.quote(field) + "[\"']?\\s*=\\s*([0-9][0-9_]*\\.[0-9_]+)")
                .matcher(line);
        String written = m.find() ? m.group(1).replace("_", "") : null;
        String name = field.equals(key) ? shown(key) : shown(key) + "." + field;
        return where + ": " + name + (written != null ? " = " + written : "")
                + " is a TOML number, which drops trailing zeros (3.10 reads as 3.1); quote it"
                + (written != null ? ": " + field + " = \"" + written + "\"" : "") + ", skipped " + shown(key);
    }

    /** The text of the line at {@code position}; empty when it isn't known. */
    private static String lineAt(List<String> lines, TomlPosition position) {
        return position == null || position.line() > lines.size() ? "" : lines.get(position.line() - 1);
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

    // .sdkmanrc: "<candidate>=<version>"; where a candidate comes twice, the first line decides

    static ProjectSpec readSdkmanrc(Path file) {
        List<String> lines = lines(file);
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        TreeSet<String> unmanaged = new TreeSet<>();
        Set<Tool> skipped = EnumSet.noneOf(Tool.class);
        Map<Tool, Integer> seen = new EnumMap<>(Tool.class); // the first line for a tool decides
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
            if (seen.containsKey(tool)) {
                warnings.add(where + ": " + shown(candidate) + " is already set on line " + seen.get(tool) + ", ignored");
                continue;
            }
            seen.put(tool, i + 1);
            try {
                tools.put(tool, SdkmanIds.parse(tool, id));
            } catch (IllegalArgumentException e) {
                skipped.add(tool);
                warnings.add(where + ": " + message(e.getMessage()) + ", skipped");
            }
        }
        if (!unmanaged.isEmpty()) {
            warnings.add(file + ": jkit doesn't manage " + shown(String.join(", ", unmanaged)) + ", skipped");
        }
        return new ProjectSpec(List.of(file), tools, warnings, skipped, Map.of());
    }

    // .tool-versions (asdf, mise): "<name> <version> [fallback versions…]"; jkit uses the first
    // version, and where a name comes twice, the first line

    static ProjectSpec readToolVersions(Path file) {
        List<String> lines = lines(file);
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        TreeSet<String> unmanaged = new TreeSet<>();
        Set<Tool> skipped = EnumSet.noneOf(Tool.class);
        Map<Tool, Integer> seen = new EnumMap<>(Tool.class); // the first line for a tool decides
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
            if (seen.containsKey(tool)) {
                warnings.add(where + ": " + shown(words[0]) + " is already set on line " + seen.get(tool) + ", ignored");
                continue;
            }
            seen.put(tool, i + 1);
            try {
                List<String> notes = new ArrayList<>();
                tools.put(tool, ToolVersionsIds.parse(tool, words[1], notes));
                notes.forEach(note -> warnings.add(where + ": " + message(note)));
            } catch (IllegalArgumentException e) {
                skipped.add(tool);
                warnings.add(where + ": " + message(e.getMessage()) + ", skipped");
            }
        }
        if (!unmanaged.isEmpty()) {
            warnings.add(file + ": jkit doesn't manage " + shown(String.join(", ", unmanaged)) + ", skipped");
        }
        return new ProjectSpec(List.of(file), tools, warnings, skipped, Map.of());
    }

    // .java-version: a Java version (21, 17.0.2, 1.8), or a jenv name (temurin64-21.0.5)

    /** jenv's names for the JDKs it knows: {@code <vendor>64-<version>}. */
    private static final Pattern JENV = Pattern.compile("([a-z][a-z-]*?)64-(\\d.*)", Pattern.CASE_INSENSITIVE);

    static ProjectSpec readJavaVersion(Path file) {
        List<String> lines = lines(file);
        for (int i = 0; i < lines.size(); i++) {
            String text = stripComment(lines.get(i));
            if (text.isEmpty()) {
                continue;
            }
            String where = file + ":" + (i + 1);
            Matcher jenv = JENV.matcher(text);
            if (jenv.matches()) {
                return jenvName(file, where, text, jenv.group(1).toLowerCase(Locale.ROOT),
                        Version.fromLegacyJava(jenv.group(2)));
            }
            String version = Version.fromLegacyJava(text);
            if (!Version.looksLikeVersion(version)) {
                return new ProjectSpec(List.of(file), Map.of(), List.of(where + ": '" + shown(text)
                        + "' is not a Java version (jkit reads numbers such as 21 or 17.0.2, and jenv names such as"
                        + " temurin64-21.0.5), skipped"), Set.of(Tool.JDK), Map.of());
            }
            return new ProjectSpec(List.of(file), Map.of(Tool.JDK, new PinnedTool(Tool.JDK, version, null, false)),
                    List.of());
        }
        return new ProjectSpec(List.of(file), Map.of(), List.of(file + ": empty, skipped"));
    }

    /**
     * A jenv name, read as the {@code .tool-versions} id {@code <vendor>-<version>}: jenv's vendor
     * names are the vendors' own ({@code temurin}, {@code corretto}, {@code zulu}). {@code openjdk}
     * is what jenv calls any build that says only "OpenJDK", so it pins the version alone.
     */
    private static ProjectSpec jenvName(Path file, String where, String text, String vendor, String version) {
        if (vendor.equals("openjdk") && Version.looksLikeVersion(version)) {
            return new ProjectSpec(List.of(file), Map.of(Tool.JDK, new PinnedTool(Tool.JDK, version, null, false)),
                    List.of());
        }
        try {
            List<String> notes = new ArrayList<>();
            PinnedTool pin = ToolVersionsIds.parse(Tool.JDK, vendor + "-" + version, notes);
            return new ProjectSpec(List.of(file), Map.of(Tool.JDK, pin),
                    notes.stream().map(note -> where + ": " + message(note)).toList());
        } catch (IllegalArgumentException e) {
            return new ProjectSpec(List.of(file), Map.of(), List.of(where + ": '" + shown(text)
                    + "' is not a Java version jkit can use, skipped"), Set.of(Tool.JDK), Map.of());
        }
    }

    // helpers

    private static List<String> lines(Path file) {
        try {
            return TextFiles.text(file).lines().toList();
        } catch (IOException e) {
            throw cannotRead(file, e);
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

    /**
     * A message about a project file entry that quotes it, as a warning shows it: escaped, and at
     * most 240 characters, as the quoted entry can be any length.
     */
    static String message(String text) {
        String t = text.length() > 240 ? text.substring(0, 240) + "…" : text;
        return Visible.of(t);
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
