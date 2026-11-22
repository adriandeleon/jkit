package dev.jkit.model;

/**
 * One tool a project file asks for. {@code version} is a prefix as in {@link Version#matches}
 * ({@code null} means newest); {@code vendor} is {@code null} when the project doesn't care.
 */
public record PinnedTool(Tool tool, String version, Vendor vendor, boolean fx) {

    public PinnedTool {
        if (version != null && (tool == Tool.JDK || tool == Tool.GRAALVM)) {
            version = Version.fromLegacyJava(version); // jdk = "1.8" is 8
        }
    }

    /** The spec for install and lookup; the vendor stays separate, as on the command line. */
    public ToolSpec spec() {
        return new ToolSpec(tool, version);
    }

    /** {@code jdk 21 zulu fx}, {@code maven 3.9}, {@code gradle (newest)}. */
    public String label() {
        return tool + " " + (version == null ? "(newest)" : version)
                + (vendor == null ? "" : " " + vendor.name()) + (fx ? " fx" : "");
    }

    @Override
    public String toString() { return label(); }
}
