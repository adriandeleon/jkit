package dev.jkit.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The fixed set of tools jkit manages. */
public enum Tool {
    JDK("jdk", "JAVA_HOME", "jdk", true),
    MAVEN("maven", "MAVEN_HOME", "maven", false),
    GRADLE("gradle", "GRADLE_HOME", "gradle", false),
    SBT("sbt", "SBT_HOME", "sbt", false),
    /** Sets only GRAALVM_HOME; shares installs with {@link #JDK}. */
    GRAALVM("graalvm", "GRAALVM_HOME", "jdk", true);

    private final String id;
    private final String homeVariable;
    private final String storeDir;
    private final boolean hasVendors;

    Tool(String id, String homeVariable, String storeDir, boolean hasVendors) {
        this.id = id;
        this.homeVariable = homeVariable;
        this.storeDir = storeDir;
        this.hasVendors = hasVendors;
    }

    public String id() { return id; }

    public String homeVariable() { return homeVariable; }

    /** Folder under tools/ holding this tool's installs. */
    public String storeDir() { return storeDir; }

    public boolean hasVendors() { return hasVendors; }

    /** GRAALVM_HOME never goes on PATH, so only one java is found. */
    public boolean onPath() { return this != GRAALVM; }

    /** The tool whose installs this tool uses. */
    public Tool storeTool() { return this == GRAALVM ? JDK : this; }

    /** The tool {@code text} names ({@code java} and {@code mvn} too), or an error with hints. */
    public static Tool parse(String text) {
        return find(text).orElseThrow(() -> new JkitException("unknown tool '" + text + "'", unknownToolHints(text)));
    }

    /** The tool {@code text} names, as {@link #parse} reads it; empty when it names none. */
    public static Optional<Tool> find(String text) {
        return Optional.ofNullable(switch (text.toLowerCase(Locale.ROOT)) {
            case "jdk", "java" -> JDK;
            case "maven", "mvn" -> MAVEN;
            case "gradle" -> GRADLE;
            case "sbt" -> SBT;
            case "graalvm" -> GRAALVM;
            default -> null;
        });
    }

    /** Vendors people type where the tool goes ({@code temurin@21}). */
    private static final Set<String> VENDOR_WORDS = Set.of("temurin", "adoptium", "zulu", "corretto",
            "liberica", "oracle", "openjdk", "microsoft", "sapmachine", "semeru", "dragonwell", "mandrel", "kona",
            "bisheng", "jetbrains", "redhat", "graalvm-ce");

    private static String[] unknownToolHints(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        List<String> names = List.of("jdk", "java", "maven", "mvn", "gradle", "sbt", "graalvm");
        List<String> hints = new ArrayList<>();
        Matcher glued = Pattern.compile("([a-z]+)-?(\\d[0-9.]*)").matcher(lower);
        if (Version.looksLikeVersion(lower)) {
            hints.add("a version goes after the tool: jdk@" + lower);
        } else if (glued.matches() && names.contains(glued.group(1))) {
            hints.add("did you mean: " + glued.group(1) + "@" + glued.group(2) + "?");
        } else if (VENDOR_WORDS.contains(lower)) {
            hints.add(lower + " is a JDK vendor; it goes after the version: jdk@21 " + lower);
        } else {
            String guess = Suggest.closest(lower, names);
            if (guess != null) {
                hints.add("did you mean: " + guess + "?");
            }
        }
        hints.add("tools: jdk, maven, gradle, sbt, graalvm");
        return hints.toArray(String[]::new);
    }

    @Override
    public String toString() { return id; }
}
