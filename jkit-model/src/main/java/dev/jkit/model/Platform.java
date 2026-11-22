package dev.jkit.model;

import java.util.Locale;

/** The five platforms jkit supports, with foojay's names for them. */
public enum Platform {
    LINUX_X64("linux-x64", "linux", "x64"),
    LINUX_AARCH64("linux-aarch64", "linux", "aarch64"),
    MACOS_X64("macos-x64", "macos", "x64"),
    MACOS_AARCH64("macos-aarch64", "macos", "aarch64"),
    WINDOWS_X64("windows-x64", "windows", "x64");

    private final String id;
    private final String os;
    private final String arch;

    Platform(String id, String os, String arch) {
        this.id = id;
        this.os = os;
        this.arch = arch;
    }

    public String id() { return id; }

    public String os() { return os; }

    public String arch() { return arch; }

    public boolean isWindows() { return os.equals("windows"); }

    public boolean isLinux() { return os.equals("linux"); }

    public static Platform current() {
        return of(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static Platform of(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String archName = osArch.toLowerCase(Locale.ROOT);
        boolean arm = archName.equals("aarch64") || archName.equals("arm64");
        boolean x64 = archName.equals("amd64") || archName.equals("x86_64");
        if (!arm && !x64) {
            throw new JkitException("unsupported CPU architecture: " + osArch);
        }
        // "darwin" contains "win", so macOS first
        if (name.contains("mac") || name.contains("darwin")) {
            return arm ? MACOS_AARCH64 : MACOS_X64;
        }
        if (name.contains("win")) {
            if (arm) {
                throw new JkitException("Windows on ARM is not supported yet");
            }
            return WINDOWS_X64;
        }
        if (name.contains("linux")) {
            return arm ? LINUX_AARCH64 : LINUX_X64;
        }
        throw new JkitException("unsupported operating system: " + osName);
    }

    @Override
    public String toString() { return id; }
}
