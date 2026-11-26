package dev.jkit.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads versions as they appear in asdf and mise {@code .tool-versions} files:
 * {@code java temurin-21.0.5+11.0.LTS}, {@code java 21}, {@code java liberica-javafx-21},
 * {@code maven 3.9.9}. The version is used as a prefix; a plain number means any vendor.
 *
 * <p>asdf-java and mise name Java builds {@code <vendor>[-jre][-javafx|-fx][-musl]-<version>}, and
 * some vendors put their own release in the version. Those are mapped to what jkit installs: a
 * Corretto {@code 21.0.5.11.1} is Java 21.0.5, a Semeru {@code 21.0.5+11_openj9-0.48.0} is
 * 21.0.5+11, a Zulu {@code 21.38.21} is a Zulu release of Java 21. jkit installs full JDKs, so a
 * {@code -jre} is pinned as the vendor's JDK, with a note saying so.
 */
public final class ToolVersionsIds {
    /** asdf-java / mise names that differ from jkit's. Others go through {@link Vendor#of}. */
    private static final Map<String, String> VENDORS = Map.of(
            "adoptopenjdk", "temurin",
            "graalvm-community", "graalvm-ce",
            "oracle-graalvm", "graalvm",
            "semeru-openj9", "semeru",
            "semeru-certified", "semeru");
    /** The vendors jkit knows by these names, after {@link #VENDORS}. */
    private static final Set<String> KNOWN = Set.of(
            "temurin", "corretto", "zulu", "liberica", "oracle", "openjdk", "graalvm-ce", "graalvm",
            "liberica-nik", "mandrel", "microsoft", "sapmachine", "semeru", "dragonwell", "kona",
            "jetbrains", "trava", "bisheng", "gluon-graalvm");
    /** Corretto's own numbering: 21.0.5.11.1 (version, build, revision) and 8.432.06.1 (8u432). */
    private static final Pattern CORRETTO = Pattern.compile("(\\d+\\.\\d+\\.\\d+)\\.\\d+\\.\\d+");
    private static final Pattern CORRETTO_EIGHT = Pattern.compile("8\\.([1-9]\\d*)\\.\\d+\\.\\d+");
    private static final Pattern MANDREL_JAVA = Pattern.compile("java(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MANDREL_RELEASE = Pattern.compile("(?<![a-z0-9])(\\d+(?:\\.\\d+)+)(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE);

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

    /** {@link #parse(Tool, String, List)}, dropping the notes. */
    public static PinnedTool parse(Tool tool, String value) {
        return parse(tool, value, new ArrayList<>());
    }

    /**
     * The pin for {@code value}; {@code notes} gets a line for each way the pin differs from what
     * the file names (a JRE pinned as the JDK, a Zulu release read as its Java major).
     *
     * @throws IllegalArgumentException with a message fit for a warning when {@code value} can't be used
     */
    public static PinnedTool parse(Tool tool, String value, List<String> notes) {
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
        if (vendorText.startsWith("mandrel")) {
            return mandrel(text, value);
        }
        boolean fx = false;
        List<String> dropped = new ArrayList<>();
        List<String> words = new ArrayList<>(List.of(vendorText.split("-")));
        for (String word : List.copyOf(words)) {
            switch (word) {
                case "javafx", "fx" -> {
                    fx = true;
                    words.remove(word);
                }
                case "jre", "lite", "musl" -> {
                    dropped.add(word);
                    words.remove(word);
                }
                default -> { }
            }
        }
        String name = String.join("-", words);
        if (name.equals("adoptopenjdk-openj9")) {
            name = "semeru";
            notes.add("'" + value + "' is an AdoptOpenJDK OpenJ9 build, pinned as semeru (IBM Semeru continues them)");
        }
        name = VENDORS.getOrDefault(name, name);
        if (name.equals("semeru")) {
            version = version.replaceFirst("_openj9-.*$", ""); // 21.0.5+11_openj9-0.48.0: the OpenJ9 release
        }
        if (name.equals("corretto")) {
            Matcher eight = CORRETTO_EIGHT.matcher(version);
            Matcher m = CORRETTO.matcher(version);
            if (eight.matches()) {
                version = "8.0." + eight.group(1);
            } else if (m.matches()) {
                version = m.group(1);
            }
        }
        if (name.equals("graalvm") && version.matches("\\d+(\\.\\d+)*\\+java\\d+")) {
            throw new IllegalArgumentException("'" + value + "' is a GraalVM release id; use the Java version instead, "
                    + "e.g. graalvm-community-21.0.2");
        }
        requireVersion(version, value);
        if (name.equals("zulu")) {
            Version v = Version.parse(version);
            if (v.partCount() > 1 && v.part(1) != 0) {
                // 21.38.21 is Zulu's release number for Java 21.0.5: only the major can be matched
                notes.add("'" + value + "' is a Zulu release number, which jkit can't match; pinned as zulu "
                        + v.part(0) + " (write the Java version, such as zulu-" + v.part(0) + ".0.<update>, to be exact)");
                version = String.valueOf(v.part(0));
            }
        }
        for (String word : dropped) {
            notes.add(switch (word) {
                case "jre" -> "'" + value + "' is a JRE; jkit installs full JDKs, pinned as the " + name + " JDK";
                case "lite" -> "'" + value + "' is a Lite build; pinned as the full " + name + " JDK";
                default -> "'" + value + "' asks for a musl build; jkit picks the C library this machine uses";
            });
        }
        if (!KNOWN.contains(name)) {
            notes.add("'" + value + "': jkit doesn't know the vendor '" + name + "'; it will look for a vendor of that "
                    + "name (jkit search jdk lists them)");
        }
        return new PinnedTool(Tool.JDK, version, Vendor.of(name), fx);
    }

    /**
     * Mandrel ids name the Mandrel release and the Java version it is built on
     * ({@code mandrel-23.1.5.0-Final-java21}, {@code mandrel-java21-23.1.5.0}); jkit lists Mandrel
     * builds as {@code 21+23.1.5}.
     */
    private static PinnedTool mandrel(String text, String value) {
        String rest = text.substring("mandrel".length());
        Matcher java = MANDREL_JAVA.matcher(rest);
        if (!java.find()) {
            throw new IllegalArgumentException("'" + value + "' names a Mandrel release but not its Java version; "
                    + "write it as mandrel-<release>-java<version>, e.g. mandrel-23.1.5.0-java21");
        }
        String feature = java.group(1);
        Matcher release = MANDREL_RELEASE.matcher(rest.replace(java.group(), ""));
        String version = release.find() ? feature + "+" + release.group(1) : feature;
        requireVersion(version, value);
        return new PinnedTool(Tool.JDK, version, Vendor.of("mandrel"), false);
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
