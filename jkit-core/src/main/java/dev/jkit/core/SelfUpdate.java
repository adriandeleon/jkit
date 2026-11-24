package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Version;
import dev.jkit.sources.Http;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code jkit self-update}: finds the newest jkit release from its SHA256SUMS, checks that file's
 * signature by jkit's release key, tells how jkit was installed (a package manager's copy is that
 * package manager's to replace), and for a copy from a release archive downloads the archive,
 * checks it against SHA256SUMS, checks the new binary runs, and puts it in place of the old one.
 */
public final class SelfUpdate {
    /** The releases page; {@code latest/download/SHA256SUMS} and {@code download/v<version>/<file>} under it. */
    public static final URI DEFAULT_BASE = URI.create("https://github.com/adriandeleon/jkit/releases/");

    private static final Pattern SUM_LINE = Pattern.compile("^([0-9a-fA-F]{64}) [ *](\\S+)$");

    private SelfUpdate() { }

    /** How this copy of jkit was installed, and so what updates it. */
    public enum Kind {
        HOMEBREW("Homebrew", "brew upgrade jkit"),
        /** A .deb from the Vitrina apt repository: apt upgrades it. */
        DEB("a .deb package", "sudo apt update && sudo apt install --only-upgrade jkit"),
        /** A .deb installed from a file, with no apt repository that has jkit: apt can't upgrade it. */
        DEB_FILE("a .deb package", null),
        RPM("an .rpm package", null),
        WINGET("winget", "winget upgrade AdrianDeLeon.jkit"),
        ARCHIVE("a release archive", null);

        public final String what;
        private final String command;

        Kind(String what, String command) {
            this.what = what;
            this.command = command;
        }

        /** The command that updates this kind of install to {@code release}; null for an archive (jkit does it). */
        public String command(Release release, Platform platform) {
            // the packages' versions have "~" for "-" (1.2.0~rc.1), which GitHub's asset names turn into "."
            String pkgVersion = release.version().raw().replace('-', '.');
            boolean arm = platform.arch().equals("aarch64");
            if (this == RPM) {
                return "sudo dnf install " + release.url(DEFAULT_BASE,
                        "jkit-" + pkgVersion + "-1." + (arm ? "aarch64" : "x86_64") + ".rpm");
            }
            if (this == DEB_FILE) {
                String file = "jkit_" + pkgVersion + "_" + (arm ? "arm64" : "amd64") + ".deb";
                return "curl -fLo /tmp/" + file + " " + release.url(DEFAULT_BASE, file) + " && sudo apt install /tmp/" + file;
            }
            return command;
        }

        public String id() {
            return this == DEB_FILE ? "deb" : name().toLowerCase(Locale.ROOT);
        }
    }

    /** The newest release: its version, the archive for this platform and that archive's SHA-256. */
    public record Release(Version version, String archive, String sha256) {
        /** Where {@code file} of this release is published under {@code base}. */
        public URI url(URI base, String file) {
            return base.resolve("download/v" + version.raw() + "/" + file);
        }
    }

    /** Checks a release's SHA256SUMS against its signature: {@link Signatures#verifyRelease}, or a test's own. */
    @FunctionalInterface
    public interface SumsCheck {
        Signatures.Result verify(byte[] sums, byte[] signature) throws IOException;
    }

    /**
     * Fetches {@code release}'s SHA256SUMS.asc and checks it signs {@code sums}, by jkit's release
     * key: the archive and SHA256SUMS sit side by side on GitHub, so whoever could replace one could
     * replace both, but not sign them.
     */
    public static void requireSigned(Http http, URI base, Release release, String sums, SumsCheck check)
            throws IOException {
        String what = "the SHA256SUMS of jkit " + release.version();
        String hint = "download jkit by hand from " + DEFAULT_BASE + " and check it with: gh attestation verify"
                + " <file> --repo adriandeleon/jkit";
        String signature;
        try {
            signature = http.getFresh(release.url(base, "SHA256SUMS.asc"));
        } catch (IOException | JkitException e) {
            if (e instanceof dev.jkit.sources.HttpStatusException status && status.status() == 404) {
                // releases before 0.4.0 have none, and waiting won't make one appear
                throw new JkitException("jkit " + release.version() + " has no signed SHA256SUMS (no SHA256SUMS.asc"
                        + " on its release page), so jkit can't check it; nothing was changed", hint);
            }
            throw new JkitException("couldn't fetch the signature of " + what + " ("
                    + dev.jkit.model.Visible.of(String.valueOf(e.getMessage())) + "); nothing was changed",
                    "try again later", "or " + hint);
        }
        Signatures.Result result = check.verify(sums.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (result.outcome() != Signatures.Outcome.GOOD) {
            throw new JkitException(what + " isn't signed by jkit's release key (" + result.detail()
                    + "); nothing was changed", hint);
        }
    }

    /** Where the newest release's SHA256SUMS is. */
    public static URI sumsUrl(URI base) {
        return base.resolve("latest/download/SHA256SUMS");
    }

    /**
     * The release SHA256SUMS describes, for {@code platform}: the .tar.gz (the .zip on Windows)
     * named {@code jkit-<version>-<os>-<arch>}. Linux builds are glibc only.
     */
    public static Release parse(String sums, Platform platform) {
        if (platform.libc() != null && platform.libc().equals("musl")) {
            throw new JkitException("there is no jkit build for musl Linux (Alpine)",
                    "jkit needs glibc 2.34 or newer");
        }
        String suffix = "-" + platform.os() + "-" + platform.arch() + (platform.isWindows() ? ".zip" : ".tar.gz");
        for (String line : sums.lines().toList()) {
            Matcher m = SUM_LINE.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            String name = m.group(2);
            if (name.startsWith("jkit-") && name.endsWith(suffix)) {
                String raw = name.substring("jkit-".length(), name.length() - suffix.length());
                if (!Version.isSafeText(raw) || raw.isEmpty()) {
                    continue;
                }
                return new Release(Version.parse(raw), name, m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        throw new JkitException("the newest jkit release has no build for " + platform.os() + "-" + platform.arch(),
                "see " + DEFAULT_BASE);
    }

    /**
     * How the binary at {@code binary} (its real path, links resolved) was installed: Homebrew's
     * Cellar, winget's package folder, a Linux package (dpkg or rpm owns /usr/bin), else an archive.
     */
    public static Kind detect(Path binary, Platform platform, Predicate<Path> exists) {
        String path = binary.toString().replace('\\', '/');
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.contains("/Cellar/jkit/") || lower.contains("/.linuxbrew/")) {
            return Kind.HOMEBREW;
        }
        if (platform.isWindows() && lower.contains("/winget/packages/")) {
            return Kind.WINGET;
        }
        if (platform.isLinux() && (path.startsWith("/usr/bin/") || path.startsWith("/usr/lib/"))) {
            if (exists.test(Path.of("/var/lib/dpkg/info/jkit.list"))) {
                // the Vitrina repository, as jkit's install guide sets it up
                boolean repo = exists.test(Path.of("/etc/apt/sources.list.d/vitrina.sources"))
                        || exists.test(Path.of("/etc/apt/sources.list.d/vitrina.list"));
                return repo ? Kind.DEB : Kind.DEB_FILE;
            }
            if (exists.test(Path.of("/var/lib/rpm")) || exists.test(Path.of("/usr/lib/sysimage/rpm"))) {
                return Kind.RPM;
            }
        }
        return Kind.ARCHIVE;
    }

    /** Whether {@code latest} is newer than {@code current}; an unreadable current version counts as older. */
    public static boolean newer(Release latest, String current) {
        if (current == null || !Version.isSafeText(current)) {
            return true;
        }
        return latest.version().compareTo(Version.parse(current)) > 0;
    }

    /**
     * Downloads the release archive into {@code work}, checks it against SHA256SUMS, unpacks it, and
     * checks the binary in it says it is that version. Returns the new binary, still in {@code work}.
     */
    public static Path fetch(Http http, URI base, Release release, Platform platform, Path work, Http.Progress progress)
            throws IOException {
        Path archive = work.resolve(release.archive());
        http.download(release.url(base, release.archive()), archive, progress);
        Checksums.verify(archive, "sha256", release.sha256(), "jkit " + release.version());
        Path out = work.resolve("out");
        Archives.extract(archive, out, platform.isWindows() ? "zip" : "tar.gz");
        Path binary = out.resolve(platform.isWindows() ? "jkit.exe" : "jkit");
        if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) {
            throw new JkitException("the jkit " + release.version() + " archive has no " + binary.getFileName()
                    + " at its top; nothing was changed");
        }
        if (!platform.isWindows()) {
            Files.setPosixFilePermissions(binary, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        String says = runVersion(binary);
        if (!says.equals("jkit " + release.version().raw())) {
            throw new JkitException("the new jkit binary doesn't run here (jkit --version said: "
                    + dev.jkit.model.Visible.of(says.isEmpty() ? "nothing" : says) + "); nothing was changed",
                    "Linux builds need glibc 2.34 or newer; macOS builds need macOS 15 or newer");
        }
        return binary;
    }

    /** What {@code binary --version} prints, first line, or empty if it fails or takes over 30 seconds. */
    static String runVersion(Path binary) {
        Path said = binary.resolveSibling("version.out");
        try {
            // into a file, not a pipe: a binary that hangs with its output open can't block the read,
            // so the time limit holds
            Process p = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true)
                    .redirectOutput(said.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE).start();
            p.getOutputStream().close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            byte[] head;
            try (java.io.InputStream in = Files.newInputStream(said)) {
                head = in.readNBytes(4096);
            }
            return new String(head, java.nio.charset.StandardCharsets.UTF_8).lines().findFirst().orElse("").strip();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            try {
                Files.deleteIfExists(said);
            } catch (IOException ignored) {
                // in the work folder, which is deleted after
            }
        }
    }

    /**
     * Puts {@code fresh} where {@code binary} is, keeping {@code binary}'s permissions. Copied next to
     * it first, then renamed over it in one step, so the old binary stays whole if anything fails.
     * Windows can't replace a running .exe, but can rename it: the old one becomes {@code jkit.exe.old},
     * which the next self-update deletes. Returns the .old file left behind, or null.
     */
    public static Path replace(Path fresh, Path binary, boolean windows) throws IOException {
        Path dir = binary.getParent();
        if (!Files.isWritable(dir)) {
            throw new JkitException("can't write to " + dir + ", where jkit is",
                    "run it as the user who owns that folder, or move jkit somewhere you own on PATH");
        }
        Path staged = dir.resolve("." + binary.getFileName() + ".new");
        Files.deleteIfExists(staged);
        Files.copy(fresh, staged);
        try {
            if (!windows) {
                Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(binary));
                Files.move(staged, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return null;
            }
            Path old = dir.resolve(binary.getFileName() + ".old");
            Files.deleteIfExists(old);
            Files.move(binary, old);
            try {
                Files.move(staged, binary);
            } catch (IOException e) {
                Files.move(old, binary); // put the old one back
                throw e;
            }
            return old;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** The jkit.exe.old an earlier self-update on Windows left; deleted when no longer running. */
    public static boolean deleteOld(Path binary) {
        try {
            return Files.deleteIfExists(binary.resolveSibling(binary.getFileName() + ".old"));
        } catch (IOException e) {
            return false;
        }
    }
}
