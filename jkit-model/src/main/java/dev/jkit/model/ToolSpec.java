package dev.jkit.model;

/**
 * A tool plus an optional version prefix, written {@code tool@version}, e.g. {@code jdk@21}.
 * A missing version or {@code latest} means "newest".
 */
public record ToolSpec(Tool tool, String version) {

    public static ToolSpec parse(String text) {
        int at = text.indexOf('@');
        if (at < 0) {
            return new ToolSpec(Tool.parse(text), null);
        }
        String version = text.substring(at + 1).trim();
        if (version.isEmpty() || version.equalsIgnoreCase("latest")) {
            version = null;
        }
        return new ToolSpec(Tool.parse(text.substring(0, at)), version);
    }

    /** The feature-release number (first component), or -1 for "newest". */
    public int major() {
        if (version == null) {
            return -1;
        }
        Version v = Version.parse(version);
        return v.part(0);
    }

    @Override
    public String toString() {
        return version == null ? tool.id() : tool.id() + "@" + version;
    }
}
