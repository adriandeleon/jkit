package dev.jkit.model;

import java.util.Locale;
import java.util.Map;

/**
 * Reads versions as they appear in asdf and mise {@code .tool-versions} files:
 * {@code java temurin-21.0.5+11.0.LTS}, {@code java 21}, {@code java liberica-javafx-21},
 * {@code maven 3.9.9}. The version is used as a prefix; a plain number means any vendor.
 */
public final class ToolVersionsIds {
    /** asdf-java / mise names that differ from jkit's. Others go through {@link Vendor#of}. */
    private static final Map<String, String> VENDORS = Map.of(
            "adoptopenjdk", "temurin",
            "graalvm-community", "graalvm-ce",
            "oracle-graalvm", "graalvm",
            "semeru-openj9", "semeru");

    private ToolVersionsIds() { }

    /** The tool for a {@code .tool-versions} name, or {@code null} for ones jkit doesn't manage. */
    public static Tool tool(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> Tool.JDK;
            case "maven" -> Tool.MAVEN;
            case "gradle" -> Tool.GRADLE;
            case "sbt" -> Tool.SBT;
            default -> null;
        };
    }

    /**
     * The pin for {@code value}.
     *
     * @throws IllegalArgumentException with a message fit for a warning when {@code value} can't be used
     */
    public static PinnedTool parse(Tool tool, String value) {
        String text = value.trim();
        if (text.equalsIgnoreCase("latest")) {
            return new PinnedTool(tool, null, null, false);
        }
        if (tool != Tool.JDK || (!text.isEmpty() && Character.isDigit(text.charAt(0)))) {
            requireVersion(text, value);
            return new PinnedTool(tool, text, null, false);
        }
        int split = vendorEnd(text);
        if (split < 0) {
            throw new IllegalArgumentException("'" + value + "' is not a version jkit can use, e.g. temurin-21 or 21");
        }
        String vendorText = text.substring(0, split).toLowerCase(Locale.ROOT);
        String version = text.substring(split + 1);
        boolean fx = false;
        for (String suffix : new String[] {"-javafx", "-fx"}) {
            if (vendorText.endsWith(suffix)) {
                fx = true;
                vendorText = vendorText.substring(0, vendorText.length() - suffix.length());
            }
        }
        requireVersion(version, value);
        Vendor vendor = Vendor.of(VENDORS.getOrDefault(vendorText, vendorText));
        return new PinnedTool(Tool.JDK, version, vendor, fx);
    }

    /** Index of the first '-' followed by a digit: {@code graalvm-community-21} → 17. */
    private static int vendorEnd(String text) {
        for (int i = 1; i < text.length() - 1; i++) {
            if (text.charAt(i) == '-' && Character.isDigit(text.charAt(i + 1))) {
                return i;
            }
        }
        return -1;
    }

    private static void requireVersion(String version, String value) {
        if (!Version.looksLikeVersion(version)) {
            throw new IllegalArgumentException("'" + value + "' is not a version jkit can use");
        }
    }
}
