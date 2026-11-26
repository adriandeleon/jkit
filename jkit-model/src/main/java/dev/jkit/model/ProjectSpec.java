package dev.jkit.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a project asks for: the files read (one {@code jkit.toml}, or {@code .sdkmanrc} and/or
 * {@code .java-version} from the same directory), the tools they pin, the file each pin came from,
 * warnings about entries that were skipped, and the tools whose pin was skipped because jkit
 * can't read it (a pin the files meant to set, unlike an unknown tool).
 */
public record ProjectSpec(List<Path> files, Map<Tool, PinnedTool> tools, List<String> warnings,
                          Set<Tool> skipped, Map<Tool, Path> origins) {

    public ProjectSpec {
        files = List.copyOf(files);
        EnumMap<Tool, PinnedTool> copy = new EnumMap<>(Tool.class);
        copy.putAll(tools);
        tools = Collections.unmodifiableMap(copy);
        warnings = List.copyOf(warnings);
        EnumSet<Tool> skip = EnumSet.noneOf(Tool.class);
        skip.addAll(skipped);
        skipped = Collections.unmodifiableSet(skip);
        EnumMap<Tool, Path> from = new EnumMap<>(Tool.class);
        from.putAll(origins);
        origins = Collections.unmodifiableMap(from);
    }

    /** One file's pins, none skipped. */
    public ProjectSpec(List<Path> files, Map<Tool, PinnedTool> tools, List<String> warnings) {
        this(files, tools, warnings, Set.of(), Map.of());
    }

    /** The directory holding the project file(s). */
    public Path dir() {
        return files.getFirst().getParent();
    }

    public PinnedTool get(Tool tool) {
        return tools.get(tool);
    }

    /** {@code jkit.toml}, or {@code .sdkmanrc, .java-version}: for "(from …)" messages. */
    public String source() {
        return String.join(", ", files.stream().map(f -> f.getFileName().toString()).toList());
    }

    /** The file that pins {@code tool}: its own entry in {@link #origins}, else the first file. */
    public Path origin(Tool tool) {
        Path from = origins.get(tool);
        return from != null ? from : files.getFirst();
    }

    /** The name of the file that pins {@code tool}, such as {@code .tool-versions}. */
    public String source(Tool tool) {
        return origin(tool).getFileName().toString();
    }
}
