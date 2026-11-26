package dev.jkit.model;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** The platforms jkit supports, with foojay's names for them; Linux comes with glibc or musl (Alpine). */
public enum Platform {
    LINUX_X64("linux-x64", "linux", "x64", "glibc"),
    LINUX_AARCH64("linux-aarch64", "linux", "aarch64", "glibc"),
    LINUX_X64_MUSL("linux-x64-musl", "linux", "x64", "musl"),
    LINUX_AARCH64_MUSL("linux-aarch64-musl", "linux", "aarch64", "musl"),
    MACOS_X64("macos-x64", "macos", "x64", null),
    MACOS_AARCH64("macos-aarch64", "macos", "aarch64", null),
    WINDOWS_X64("windows-x64", "windows", "x64", null);

    private final String id;
    private final String os;
    private final String arch;
    private final String libc;

    Platform(String id, String os, String arch, String libc) {
        this.id = id;
        this.os = os;
        this.arch = arch;
        this.libc = libc;
    }

    /** foojay's lib_c_type for Linux builds (glibc or musl); null elsewhere. */
    public String libc() { return libc; }

    public String id() { return id; }

    public String os() { return os; }

    public String arch() { return arch; }

    public boolean isWindows() { return os.equals("windows"); }

    public boolean isLinux() { return os.equals("linux"); }

    /**
     * Whether this jkit runs on Windows: what the file system and processes here behave like.
     * Unlike {@link #current()}, it never fails and looks at no files.
     */
    public static boolean hostIsWindows() {
        return File.separatorChar == '\\';
    }

    public static Platform current() {
        Platform p = of(System.getProperty("os.name"), System.getProperty("os.arch"));
        return p.isLinux() && isMusl(p.arch()) ? p.withMusl() : p;
    }

    /**
     * Alpine and other musl systems: a glibc JDK there fails with "not found" from the missing
     * loader. Debian's musl package adds ld-musl next to glibc, so ld-musl alone isn't enough; Alpine
     * with gcompat has a glibc loader shim, so /etc/alpine-release counts too.
     */
    private static boolean isMusl(String arch) {
        String a = arch.equals("x64") ? "x86_64" : "aarch64";
        if (!Files.exists(Path.of("/lib/ld-musl-" + a + ".so.1"))) {
            return false;
        }
        boolean glibcLoader = Files.exists(Path.of(
                arch.equals("x64") ? "/lib64/ld-linux-x86-64.so.2" : "/lib/ld-linux-aarch64.so.1"));
        return !glibcLoader || Files.exists(Path.of("/etc/alpine-release"));
    }

    /** The musl variant of a Linux platform. */
    public Platform withMusl() {
        return switch (this) {
            case LINUX_X64, LINUX_X64_MUSL -> LINUX_X64_MUSL;
            case LINUX_AARCH64, LINUX_AARCH64_MUSL -> LINUX_AARCH64_MUSL;
            default -> throw new IllegalStateException(this + " has no musl variant");
        };
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
