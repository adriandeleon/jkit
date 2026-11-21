package dev.jkit.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a project asks for: the files read (one {@code jkit.toml}, or {@code .sdkmanrc} and/or
 * {@code .java-version} from the same directory), the tools they pin, and warnings about
 * entries that were skipped.
 */
public record ProjectSpec(List<Path> files, Map<Tool, PinnedTool> tools, List<String> warnings) {

    public ProjectSpec {
        files = List.copyOf(files);
        EnumMap<Tool, PinnedTool> copy = new EnumMap<>(Tool.class);
        copy.putAll(tools);
        tools = Collections.unmodifiableMap(copy);
        warnings = List.copyOf(warnings);
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
}
