package dev.jkit.model;

/**
 * A tool plus an optional version prefix, written {@code tool@version}, e.g. {@code jdk@21}.
 * A missing version or {@code latest} means "newest"; {@code jdk@lts} (and graalvm) stands for the
 * newest long-term-support release until the CLI resolves it to a number.
 */
public record ToolSpec(Tool tool, String version) {
    public static final String LTS = "lts";

    /** {@code jdk@lts}: the CLI swaps it for a feature release before using it as a version. */
    public boolean isLts() {
        return LTS.equals(version);
    }

    /**
     * Long-term-support feature releases by Oracle's current cadence: 8, 11, then every fourth from
     * 17 (17, 21, 25, 29, …). Only the offline fallback: online, {@code jdk@lts} is what foojay marks
     * as LTS; this formula picks among installed releases when there is no network, and would need
     * updating if the cadence changes.
     */
    public static boolean isLtsMajor(int major) {
        return major == 8 || major == 11 || (major >= 17 && (major - 17) % 4 == 0);
    }

    /** Whether {@code text} asks for {@code @latest} by name (parse makes it a missing version). */
    public static boolean saysLatest(String text) {
        int at = text.indexOf('@');
        return at >= 0 && text.substring(at + 1).trim().equalsIgnoreCase("latest");
    }

    public static ToolSpec parse(String text) {
        int at = text.indexOf('@');
        if (at < 0) {
            return new ToolSpec(Tool.parse(text.trim()), null);
        }
        Tool tool = Tool.parse(text.substring(0, at).trim());
        String version = text.substring(at + 1).trim();
        if (version.isEmpty() || version.equalsIgnoreCase("latest")) {
            return new ToolSpec(tool, null);
        }
        if (version.equalsIgnoreCase(LTS)) {
            if (tool != Tool.JDK && tool != Tool.GRAALVM) {
                throw new JkitException("'" + text.trim() + "': only JDKs have LTS releases",
                        "e.g. " + tool.id() + "@latest");
            }
            return new ToolSpec(tool, LTS);
        }
        if (tool == Tool.JDK || tool == Tool.GRAALVM) {
            version = Version.fromLegacyJava(version); // jdk@1.8 is jdk@8
        }
        if (!Version.looksLikeVersion(version)) {
            throw new JkitException("'" + text.trim() + "': '" + version + "' is not a version",
                    "e.g. " + tool.id() + "@21, " + tool.id() + "@21.0.5, or " + tool.id() + "@latest");
        }
        return new ToolSpec(tool, version);
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
