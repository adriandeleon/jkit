package dev.jkit.model;

import java.util.Locale;

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

    public static Tool parse(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "jdk", "java" -> JDK;
            case "maven", "mvn" -> MAVEN;
            case "gradle" -> GRADLE;
            case "sbt" -> SBT;
            case "graalvm" -> GRAALVM;
            default -> throw new JkitException(
                    "unknown tool '" + text + "'", "tools: jdk, maven, gradle, sbt, graalvm");
        };
    }

    @Override
    public String toString() { return id; }
}
