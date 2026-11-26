package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import dev.jkit.sources.Http;
import dev.jkit.sources.HttpStatusException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * The Visual C++ runtime the Windows .zip ships next to jkit.exe (the native image links it, and
     * Windows doesn't include it), so the .zip runs without the Visual C++ Redistributable.
     */
    public static final List<String> WINDOWS_RUNTIME = List.of("vcruntime140.dll", "vcruntime140_1.dll");

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

        /**
         * Whether the command needs the release's signed SHA256SUMS: an .rpm or .deb installed from
         * a file, which dnf and apt don't check a signature of (dnf's localpkg_gpgcheck is off by
         * default, and apt checks only its repositories), so the command checks the file's hash.
         */
        public boolean needsSignedSums() {
            return this == RPM || this == DEB_FILE;
        }

        /** The .rpm or .deb this kind of install updates from, as the release publishes it; null for the others. */
        public String packageFile(Release release, Platform platform) {
            // the packages' versions have "~" for "-" (1.2.0~rc.1), which GitHub's asset names turn into "."
            String pkgVersion = release.version().raw().replace('-', '.');
            boolean arm = platform.arch().equals("aarch64");
            return switch (this) {
                case RPM -> "jkit-" + pkgVersion + "-1." + (arm ? "aarch64" : "x86_64") + ".rpm";
                case DEB_FILE -> "jkit_" + pkgVersion + "_" + (arm ? "arm64" : "amd64") + ".deb";
                default -> null;
            };
        }

        /**
         * The command that updates this kind of install to {@code release}; null for an archive
         * (jkit does it). For an .rpm or .deb from a file, {@code sums} is the release's
         * SHA256SUMS, which the caller has checked the signature of ({@link #requireSigned}): the
         * command downloads the package into a new folder of the user's own and installs it only if
         * its SHA-256 is the one listed there. A new folder, not a fixed name in /tmp: another user
         * could create that file first, writable by them, and swap in their own package before sudo
         * reads it.
         */
        public String command(Release release, Platform platform, String sums) {
            String file = packageFile(release, platform);
            if (file == null) {
                return command;
            }
            String sha256 = SelfUpdate.sha256(sums, file);
            if (sha256 == null) {
                throw new JkitException("jkit " + release.version() + " has no " + file + " in its SHA256SUMS",
                        "see " + DEFAULT_BASE);
            }
            String path = "\"$d/" + file + "\"";
            return "d=$(mktemp -d) && curl -fLo " + path + " " + release.url(DEFAULT_BASE, file)
                    + " && echo '" + sha256 + "  '" + path + " | sha256sum -c -"
                    + " && sudo " + (this == RPM ? "dnf" : "apt") + " install " + path;
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
            if (e instanceof HttpStatusException status && status.status() == 404) {
                // releases before 0.4.0 have none, and waiting won't make one appear
                throw new JkitException("jkit " + release.version() + " has no signed SHA256SUMS (no SHA256SUMS.asc"
                        + " on its release page), so jkit can't check it; nothing was changed", hint);
            }
            throw new JkitException("couldn't fetch the signature of " + what + " ("
                    + Visible.of(String.valueOf(e.getMessage())) + "); nothing was changed",
                    "try again later", "or " + hint);
        }
        Signatures.Result result = check.verify(sums.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        if (result.outcome() != Signatures.Outcome.GOOD) {
            throw new JkitException(what + " isn't signed by jkit's release key (" + result.detail()
                    + "); nothing was changed", hint);
        }
    }

    /** The SHA-256 {@code sums} lists for exactly {@code file}, lower case; null when it lists none. */
    static String sha256(String sums, String file) {
        for (String line : sums.lines().toList()) {
            Matcher m = SUM_LINE.matcher(line.strip());
            if (m.matches() && m.group(2).equals(file)) {
                return m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** Where the newest release's SHA256SUMS is. */
    public static URI sumsUrl(URI base) {
        return base.resolve("latest/download/SHA256SUMS");
    }

    /**
     * The release SHA256SUMS describes, for {@code platform}: the .tar.gz (the .zip on Windows)
     * named {@code jkit-<version>-<platform>}, where a musl Linux platform ends in {@code -musl}
     * (jkit-0.4.0-linux-x64-musl.tar.gz).
     */
    public static Release parse(String sums, Platform platform) {
        String suffix = "-" + platform.id() + (platform.isWindows() ? ".zip" : ".tar.gz");
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
        throw new JkitException("the newest jkit release has no build for " + platform.id(),
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

    /**
     * Whether {@code latest} is newer than {@code current}; an unreadable current version counts as
     * older. A pre-release is newer only than a pre-release: a release candidate marked as the
     * latest release, by mistake or not, mustn't move everyone on a release onto it.
     */
    public static boolean newer(Release latest, String current) {
        boolean readable = current != null && Version.isSafeText(current);
        if (latest.version().isPreRelease() && !(readable && Version.parse(current).isPreRelease())) {
            return false;
        }
        return !readable || latest.version().compareTo(Version.parse(current)) > 0;
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
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        String says = runVersion(binary);
        if (!says.equals("jkit " + release.version().raw())) {
            throw new JkitException("the new jkit binary doesn't run here (jkit --version said: "
                    + Visible.of(says.isEmpty() ? "nothing" : says) + "); nothing was changed",
                    platform.isLinux() && "musl".equals(platform.libc())
                            ? "the musl Linux builds are static and need nothing from the system"
                            : "Linux builds need glibc 2.34 or newer; macOS builds need macOS 14 or newer");
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
            try (InputStream in = Files.newInputStream(said)) {
                head = in.readNBytes(4096);
            }
            return new String(head, StandardCharsets.UTF_8).lines().findFirst().orElse("").strip();
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
     * Windows can't replace a running .exe, but can rename it: the old one becomes {@code jkit.exe.old}
     * ({@code jkit.exe.1.old} and so on while an older jkit still running holds that one), which the
     * next self-update deletes. Returns the .old file left behind, or null.
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
            Path old = freeOld(binary);
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

    /**
     * The runtime DLLs an update on Windows replaces along with {@code binary}: those next to it that
     * the new archive (unpacked around {@code fresh}) has too. A DLL that isn't next to jkit.exe is
     * never added: a folder jkit.exe shares with other programs would make them load it too, and a
     * jkit that runs without the DLLs beside it has the runtime installed already.
     */
    public static List<String> runtimeToReplace(Path fresh, Path binary) {
        List<String> names = new ArrayList<>();
        for (String name : WINDOWS_RUNTIME) {
            if (Files.isRegularFile(binary.resolveSibling(name), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(fresh.resolveSibling(name), LinkOption.NOFOLLOW_LINKS)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The .old files an earlier self-update on Windows left (jkit.exe.old, and the runtime DLLs'),
     * deleted once nothing has them open. Returns the ones deleted.
     */
    public static List<Path> deleteOld(Path binary) {
        List<String> names = new ArrayList<>();
        names.add(binary.getFileName().toString());
        names.addAll(WINDOWS_RUNTIME);
        List<Path> olds;
        try (var files = Files.list(binary.getParent())) {
            olds = files.filter(p -> names.stream().anyMatch(n -> isOld(p.getFileName().toString(), n)))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of(); // the next self-update tries again
        }
        List<Path> deleted = new ArrayList<>();
        for (Path old : olds) {
            try {
                if (Files.deleteIfExists(old)) {
                    deleted.add(old);
                }
            } catch (IOException e) {
                // still open (the jkit that left it is running): the next self-update tries again
            }
        }
        return deleted;
    }

    /** How many {@code jkit.exe.<n>.old} names {@link #freeOld} tries before it gives up. */
    static final int MAX_OLD = 100;

    /**
     * Where {@code file} goes while it is replaced: {@code <name>.old}, deleting the one an earlier
     * update left; or, when that can't be deleted because an older jkit still running holds it,
     * the first free {@code <name>.<n>.old}. {@link #deleteOld} deletes them all once they are free.
     */
    static Path freeOld(Path file) throws IOException {
        String name = file.getFileName().toString();
        Path old = file.resolveSibling(name + ".old");
        try {
            Files.deleteIfExists(old);
            return old;
        } catch (IOException held) {
            for (int n = 1; n <= MAX_OLD; n++) {
                Path other = file.resolveSibling(name + "." + n + ".old");
                if (!Files.exists(other, LinkOption.NOFOLLOW_LINKS)) {
                    return other;
                }
            }
            throw held;
        }
    }

    /** Whether {@code fileName} is {@code <name>.old} or {@code <name>.<n>.old}, a name {@link #freeOld} gives. */
    static boolean isOld(String fileName, String name) {
        if (!fileName.startsWith(name + ".") || !fileName.endsWith(".old")) {
            return false;
        }
        String middle = fileName.substring(name.length(), fileName.length() - ".old".length());
        return middle.isEmpty() || middle.matches("\\.[1-9][0-9]{0,2}");
    }
}
