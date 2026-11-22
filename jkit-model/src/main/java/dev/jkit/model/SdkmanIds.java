package dev.jkit.model;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads SDKMAN! version identifiers as they appear in {@code .sdkmanrc}: {@code java=21.0.5-tem},
 * {@code java=21.0.5.fx-zulu}, {@code maven=3.9.9}. SDKMAN ids carry no build number, so the
 * version is used as a prefix.
 */
public final class SdkmanIds {
    /** SDKMAN's vendor suffix to the vendor name jkit users type. */
    private static final Map<String, String> VENDORS = Map.ofEntries(
            Map.entry("tem", "temurin"),
            Map.entry("zulu", "zulu"),
            Map.entry("amzn", "corretto"),
            Map.entry("librca", "liberica"),
            Map.entry("ms", "microsoft"),
            Map.entry("sapmchn", "sapmachine"),
            Map.entry("sem", "semeru"),
            Map.entry("graalce", "graalvm-ce"),
            Map.entry("graal", "graalvm"),
            Map.entry("nik", "liberica-nik"),
            Map.entry("mandrel", "mandrel"),
            Map.entry("oracle", "oracle"),
            Map.entry("open", "openjdk"),
            Map.entry("kona", "kona"),
            Map.entry("albba", "dragonwell"),
            Map.entry("jbr", "jetbrains"),
            Map.entry("trava", "trava"),
            Map.entry("bsg", "bisheng"));
    /** Old GraalVM and Mandrel ids name the distribution release, not the Java version. */
    private static final Pattern DISTRIBUTION_RELEASE = Pattern.compile(".*\\.r\\d+$");

    private SdkmanIds() { }

    /** The tool for an {@code .sdkmanrc} candidate name, or {@code null} for ones jkit doesn't manage. */
    public static Tool tool(String candidate) {
        return switch (candidate.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> Tool.JDK;
            case "maven" -> Tool.MAVEN;
            case "gradle" -> Tool.GRADLE;
            default -> null;
        };
    }

    /**
     * The pin for {@code id}. GraalVM-based Java ids stay {@link Tool#JDK}, as in SDKMAN, where they
     * become JAVA_HOME.
     *
     * @throws IllegalArgumentException with a message fit for a warning when {@code id} can't be used
     */
    public static PinnedTool parse(Tool tool, String id) {
        String text = id.trim();
        if (tool != Tool.JDK) {
            requireVersion(text, id);
            return new PinnedTool(tool, text, null, false);
        }
        int dash = text.lastIndexOf('-');
        if (dash <= 0 || dash == text.length() - 1) {
            throw new IllegalArgumentException("'" + id + "' has no vendor suffix, e.g. 21.0.5-tem");
        }
        String version = text.substring(0, dash);
        String suffix = text.substring(dash + 1).toLowerCase(Locale.ROOT);
        boolean fx = false;
        if (version.toLowerCase(Locale.ROOT).endsWith(".fx")) {
            fx = true;
            version = version.substring(0, version.length() - 3);
        }
        if (DISTRIBUTION_RELEASE.matcher(version).matches()) {
            throw new IllegalArgumentException("'" + id + "' is a GraalVM/Mandrel release id; "
                    + "use the Java version instead, e.g. 21.0.5-graalce");
        }
        // SDKMAN writes early-access builds as 26.ea.20; foojay as 26-ea+20
        version = version.replaceFirst("^(\\d+)\\.ea\\.(\\d+)$", "$1-ea+$2").replaceFirst("^(\\d+)\\.ea$", "$1-ea");
        requireVersion(version, id);
        String vendor = VENDORS.get(suffix);
        if (vendor == null) {
            throw new IllegalArgumentException("unknown SDKMAN vendor '" + suffix + "' in '" + id + "'");
        }
        return new PinnedTool(Tool.JDK, version, Vendor.of(vendor), fx);
    }

    private static void requireVersion(String version, String id) {
        if (!Version.looksLikeVersion(version)) {
            throw new IllegalArgumentException("'" + id + "' is not a version");
        }
    }
}
