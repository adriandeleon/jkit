package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.SdkmanIds;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolVersionsIds;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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
            if (!Files.isRegularFile(file)) {
                throw new JkitException(OVERRIDE + " points to " + file + ", which is not a file",
                        "unset it to search for jkit.toml, or set it empty to turn project files off");
            }
            return Optional.of(read(file));
        }
        Path dir = start.toAbsolutePath().normalize();
        Path home = home(env);
        boolean belowHome = home != null && dir.startsWith(home);
        for (; dir != null; dir = dir.getParent()) {
            Optional<ProjectSpec> found = inDir(dir);
            if (found.isPresent() || (belowHome && dir.equals(home))) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Path home(Map<String, String> env) {
        String home = env.getOrDefault("HOME", System.getProperty("user.home"));
        return home == null || home.isBlank() ? null : Path.of(home).toAbsolutePath().normalize();
    }

    /**
     * {@code jkit.toml} (or {@code .jkit.toml}) alone; otherwise the other formats together, earlier
     * ones winning per tool: {@code .sdkmanrc}, {@code .tool-versions}, {@code .java-version}.
     */
    private static Optional<ProjectSpec> inDir(Path dir) {
        Path toml = dir.resolve(JKIT_TOML);
        Path hidden = dir.resolve(HIDDEN_TOML);
        if (Files.isRegularFile(toml)) {
            ProjectSpec spec = readToml(toml);
            if (Files.isRegularFile(hidden)) {
                spec = withWarning(spec, hidden + ": ignored, " + JKIT_TOML + " in the same folder wins");
            }
            return Optional.of(spec);
        }
        if (Files.isRegularFile(hidden)) {
            return Optional.of(readToml(hidden));
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
        return switch (name) {
            case SDKMANRC -> readSdkmanrc(file);
            case TOOL_VERSIONS -> readToolVersions(file);
            case JAVA_VERSION -> readJavaVersion(file);
            default -> readToml(file);
        };
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
        TomlParseResult toml;
        try {
            toml = Toml.parse(file);
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
        if (toml.hasErrors()) {
            TomlParseError error = toml.errors().getFirst();
            throw new JkitException(at(file, error.position()) + ": invalid TOML: " + error.getMessage());
        }
        List<String> warnings = new ArrayList<>();
        Map<Tool, PinnedTool> tools = new EnumMap<>(Tool.class);
        Map<Tool, String> keyFor = new EnumMap<>(Tool.class);
        for (String key : toml.keySet()) {
            if (!key.equals("tools")) {
                warnings.add(at(file, toml.inputPositionOf(List.of(key))) + ": unknown key '" + key + "', skipped");
            }
        }
        if (toml.contains("tools") && !toml.isTable("tools")) {
            warnings.add(at(file, toml.inputPositionOf("tools")) + ": 'tools' should be a table ([tools]), skipped");
        } else if (toml.isTable("tools")) {
            TomlTable table = toml.getTable("tools");
            for (String key : table.keySet()) {
                String where = at(file, toml.inputPositionOf(List.of("tools", key)));
                Tool tool;
                try {
                    tool = Tool.parse(key);
                } catch (JkitException e) {
                    warnings.add(where + ": unknown tool '" + key + "', skipped (tools: jdk, maven, gradle, graalvm)");
                    continue;
                }
                if (keyFor.containsKey(tool)) {
                    throw new JkitException(where + ": '" + key + "' and '" + keyFor.get(tool) + "' both set " + tool,
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
            warnings.add(where + ": " + key + " should be a version string or a table, skipped");
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
                        warnings.add(where + ": " + key + ".version should be a string, skipped " + key);
                        return null;
                    }
                    version = s;
                }
                case "vendor" -> {
                    if (!(v instanceof String s)) {
                        warnings.add(where + ": " + key + ".vendor should be a string, skipped " + key);
                        return null;
                    }
                    vendor = s;
                }
                case "fx" -> {
                    if (!(v instanceof Boolean b)) {
                        warnings.add(where + ": " + key + ".fx should be true or false, skipped " + key);
                        return null;
                    }
                    fx = b;
                }
                default -> warnings.add(where + ": unknown field '" + field + "' in " + key + ", ignored");
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
            warnings.add(where + ": " + key + " = '" + versionText + "' is not a version, skipped");
            return null;
        }
        Vendor vendor = null;
        if (vendorText != null && !vendorText.isBlank()) {
            if (!tool.hasVendors()) {
                warnings.add(where + ": " + tool + " has no vendors; vendor '" + vendorText + "' ignored");
            } else {
                vendor = Vendor.of(vendorText);
                if (tool == Tool.GRAALVM && !vendor.isGraalVmBased()) {
                    warnings.add(where + ": " + vendor + " is not GraalVM-based, skipped " + key
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
                warnings.add(where + ": " + e.getMessage() + ", skipped");
            }
        }
        if (!unmanaged.isEmpty()) {
            warnings.add(file + ": jkit doesn't manage " + String.join(", ", unmanaged) + ", skipped");
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
                warnings.add(where + ": " + e.getMessage() + ", skipped");
            }
        }
        if (!unmanaged.isEmpty()) {
            warnings.add(file + ": jkit doesn't manage " + String.join(", ", unmanaged) + ", skipped");
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
                return new ProjectSpec(List.of(file), Map.of(), List.of(file + ":" + (i + 1) + ": '" + text
                        + "' is not a Java version (jkit reads only numbers such as 21 or 17.0.2), skipped"));
            }
            return new ProjectSpec(List.of(file), Map.of(Tool.JDK, new PinnedTool(Tool.JDK, version, null, false)),
                    List.of());
        }
        return new ProjectSpec(List.of(file), Map.of(), List.of(file + ": empty, skipped"));
    }

    /** {@code 1.8} → {@code 8}, {@code 1.8.0_392} → {@code 8.0_392}: the pre-9 numbering. */
    static String legacyJavaVersion(String text) {
        if (text.matches("1\\.[1-8](\\D.*)?")) {
            return text.substring(2);
        }
        return text;
    }

    // helpers

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw cannotRead(file, e);
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
