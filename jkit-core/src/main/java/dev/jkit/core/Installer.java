package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import dev.jkit.model.Tool;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.Http;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Download, check checksum, unpack into tmp/, check the tool's executable, then move into
 * tools/ in one rename. An interrupted install leaves nothing in tools/.
 * <p>
 * The install lock is held only to look at tools/ and to change it: to see whether the build is
 * already installed and clear what interrupted installs left in tmp/, and again for the move.
 * The download and checks in between run in a work folder of this process's own ({@link
 * WorkDirs#create}), so two installs of different tools, in two terminals or CI jobs, download
 * side by side, and remove, prune or self-update don't wait for a download.
 */
public final class Installer {

    /**
     * Thrown, with nothing installed, when a download with no checksum was to be checked by its
     * signature alone and the signature couldn't be fetched or checked.
     */
    public static final class SignatureRequired extends JkitException {
        SignatureRequired(String message) {
            super(message);
        }
    }

    /**
     * @param verified true when the download matched a sha256-or-stronger checksum or a good
     *                 signature by a key built into jkit; false for md5 or sha1 only, or nothing
     * @param removedLeftovers tmp/ folders of interrupted installs that this install deleted
     * @param signedBy whose OpenPGP signature was checked (Adoptium, Amazon, Apache Maven, ...), or null
     * @param notes what the signature check found, for the user: checked, or why it couldn't be
     */
    public record Result(Installed installed, boolean fresh, boolean verified, List<Path> removedLeftovers,
            String signedBy, List<String> notes) {
        public Result(Installed installed, boolean fresh, boolean verified, List<Path> removedLeftovers) {
            this(installed, fresh, verified, removedLeftovers, null, List.of());
        }
    }

    private final JkitHome home;
    private final Http http;

    public Installer(JkitHome home, Http http) {
        this.home = home;
        this.http = http;
    }

    public Result install(Artifact artifact, Http.Progress progress) throws IOException {
        return install(artifact, progress, false);
    }

    /**
     * @param signatureRequired a signature that can't be fetched or checked throws
     *                          {@link SignatureRequired} rather than installing on the checksum
     *                          alone: jkit's commands require one whenever the publisher signs its
     *                          downloads, unless --allow-unverified
     */
    @SuppressWarnings("try") // the lock is held for the block, unused inside it
    public Result install(Artifact artifact, Http.Progress progress, boolean signatureRequired) throws IOException {
        Http.requireSecure(artifact.url());
        Tool storeTool = artifact.tool().storeTool();
        Path toolsDir = home.tools(storeTool);
        String dirName = Installed.dirName(artifact.version(), artifact.vendor(), artifact.fx());
        Path dest = toolsDir.resolve(dirName).normalize();
        // the folder's bin goes on PATH: no ":" or ";" (PATH separators), "/", spaces or controls
        if (!dirName.matches("[0-9A-Za-z][0-9A-Za-z.+_~-]{0,160}") || !toolsDir.equals(dest.getParent())) {
            throw new JkitException("refusing an install folder outside " + toolsDir + ": " + dest);
        }
        Installed installed = new Installed(storeTool, artifact.version(), artifact.vendor(), dest, artifact.fx());
        home.create();
        List<Path> removed;
        Path work;
        try (Store.Lock held = lock()) {
            Result existing = existing(installed, List.of());
            if (existing != null) {
                return existing;
            }
            Perms.mkdirs(home.tmp());
            removed = removeLeftovers(home.tmp());
            // made under the lock, so no other jkit takes it for a leftover before it is held
            work = WorkDirs.create(home.tmp(), "install-"); // Ctrl-C deletes it too (jkit's signal handler)
        }
        IOException failure = null;
        try {
            List<String> notes = new ArrayList<>();
            boolean signable = artifact.signature() != null && Signatures.covers(artifact.tool(), artifact.vendor());
            if (signatureRequired && !signable) {
                throw new SignatureRequired(artifact.label() + " has no published checksum or signature jkit can"
                        + " check; nothing was installed");
            }
            // the signature first: a few hundred bytes that may already refuse the install (none
            // published, or made with a key jkit doesn't have), which then doesn't wait for the download
            byte[] signature = signable ? fetchSignature(artifact, work, notes, signatureRequired) : null;
            Path archive = work.resolve(safeFileName(artifact.fileName(), artifact.archiveType()));
            http.download(artifact.url(), archive, progress);
            boolean verified = false;
            if (artifact.checksum() != null && artifact.checksumAlgorithm() != null) {
                // md5 and sha1 still catch a broken download, but don't count as verified
                Checksums.verify(archive, artifact.checksumAlgorithm(), artifact.checksum(), artifact.label());
                verified = Checksums.strong(artifact.checksumAlgorithm());
            }
            String signedBy = signature != null ? checkSignature(artifact, archive, signature, notes, signatureRequired)
                    : null;
            if (artifact.signature() == null && artifact.checksum() != null
                    && Signatures.covers(artifact.tool(), artifact.vendor())) {
                // Temurin before mid-2022, Maven before 3.9.0: said, so the user knows what was checked
                notes.add("note: no signature jkit can check is published for " + artifact.label()
                        + "; only its checksum was checked");
            }
            // a good signature by a built-in key is a stronger check than any checksum; one that
            // is published but couldn't be checked (--allow-unverified) leaves the install unverified:
            // whoever can replace the download can usually replace its checksum too
            verified = signedBy != null || (verified && !signable);
            // made here, not by extract with its parents: once Ctrl-C has deleted the work folder this fails
            Path out = Files.createDirectory(work.resolve("out"));
            try {
                Archives.extract(archive, out, artifact.archiveType());
            } catch (IOException e) {
                // truncated or corrupt (EOF, "not a ZIP archive"): the download, not this disk
                if (e instanceof FileSystemException) {
                    throw e;
                }
                throw new JkitException("the downloaded archive for " + artifact.label() + " is damaged ("
                        + Visible.of(String.valueOf(e.getMessage())) + "); nothing was installed",
                        "try again; if it keeps happening, the source has a broken file: pick another version");
            }
            Path toolHome = locateHome(out);
            requireLinksInside(toolHome);
            requireExecutable(storeTool, toolHome, artifact.label());
            if (signedBy != null) {
                // a signature covers the file, not its name: any build the publisher signed would
                // pass, an older one included, and the checksum next to it may come from the same
                // server as the download. The unpacked tool must be the version asked for
                requireSignedVersion(storeTool, toolHome, artifact);
            }
            Store.writeMarker(toolHome, installed, artifact.url().toString(), verified, signedBy);
            try (Store.Lock held = lock()) {
                // another jkit may have installed the same build meanwhile: theirs stays, ours goes
                Result existing = existing(installed, removed);
                if (existing != null) {
                    return existing;
                }
                Perms.mkdirs(dest.getParent());
                moveIntoPlace(toolHome, dest);
            }
            return new Result(installed, true, verified, removed, signedBy, List.copyOf(notes));
        } catch (IOException | RuntimeException e) {
            if (e instanceof IOException io) {
                failure = io;
            }
            throw e;
        } finally {
            WorkDirs.remove(work); // its lock first: Windows deletes no open file
            try {
                Fs.deleteRecursively(work);
            } catch (IOException e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                }
                // a leftover in tmp/ is cleaned up by the next install; don't hide the real result
            }
        }
    }

    /**
     * The build already installed, as a result that says so, or null when it isn't. Only called
     * with the lock held.
     */
    private static Result existing(Installed installed, List<Path> removed) {
        Path dest = installed.home();
        if (!Files.isDirectory(dest)) {
            return null;
        }
        if (!Files.isRegularFile(dest.resolve(Store.MARKER), LinkOption.NOFOLLOW_LINKS)) {
            throw new JkitException(dest + " exists but isn't a jkit install (no " + Store.MARKER + ")",
                    "move or delete that folder, then install again");
        }
        return new Result(installed, false, Store.verified(dest), removed);
    }

    /**
     * Fetches the publisher's signature and looks at what can be told of it without the download:
     * who made it, and whether it is of a kind jkit accepts. Returns it when checking the download
     * decides. One jkit can't fetch or check refuses the install when {@code required}; otherwise
     * the checksum is the only check, {@code notes} says so, and this returns null.
     */
    private byte[] fetchSignature(Artifact artifact, Path work, List<String> notes, boolean required)
            throws IOException {
        Path file = work.resolve("download.sig");
        byte[] bytes;
        try {
            // stopped at the limit: a signature link that serves a JDK's worth of data isn't read whole
            http.download(artifact.signature(), file, Http.Progress.NONE, Signatures.MAX_SIGNATURE);
            bytes = Files.readAllBytes(file);
        } catch (IOException | JkitException e) {
            String why = "couldn't fetch the signature of " + artifact.label() + " ("
                    + Visible.of(String.valueOf(e.getMessage())) + ")";
            if (required) {
                throw new SignatureRequired(why + refused(artifact));
            }
            notes.add("warning: " + why + unchecked(artifact));
            return null;
        }
        Signatures.Result early = Signatures.precheck(bytes, artifact.tool(), artifact.vendor());
        if (early == null) {
            return bytes;
        }
        uncheckable(artifact, early, notes, required);
        return null;
    }

    /**
     * Checks the archive with the signature {@link #fetchSignature} returned. One that doesn't
     * match refuses the install. Returns who signed it, or null.
     */
    private static String checkSignature(Artifact artifact, Path archive, byte[] signature, List<String> notes,
                                         boolean required) throws IOException {
        Signatures.Result result = Signatures.verify(archive, signature, artifact.tool(), artifact.vendor());
        switch (result.outcome()) {
            case GOOD -> {
                notes.add("signature checked: signed by " + result.detail());
                return result.signer();
            }
            case BAD -> throw new JkitException("the signature of " + artifact.label() + " doesn't match the download ("
                    + result.detail() + "); nothing was installed",
                    "try again; if it keeps happening, the download or the signature is wrong at the source: "
                            + "pick another build or vendor");
            default -> {
                uncheckable(artifact, result, notes, required);
                return null;
            }
        }
    }

    /** A signature jkit can't check: refuses the install when {@code required}, else a warning in {@code notes}. */
    private static void uncheckable(Artifact artifact, Signatures.Result result, List<String> notes, boolean required) {
        String why = "couldn't check the signature of " + artifact.label() + ": " + result.detail();
        // a key jkit doesn't know may be the publisher's new one; a retired one stays unknown
        String newer = result.outcome() == Signatures.Outcome.UNKNOWN_KEY
                ? " (a newer jkit may know a new key: jkit self-update)"
                : result.outcome() == Signatures.Outcome.RETIRED_KEY ? " (pick a newer build)" : "";
        if (required) {
            throw new SignatureRequired(why + newer + refused(artifact));
        }
        notes.add("warning: " + why + newer + unchecked(artifact));
    }

    private static String unchecked(Artifact artifact) {
        return artifact.checksum() == null ? "; installed unchecked (--allow-unverified)"
                : "; installed with only its checksum checked";
    }

    private static String refused(Artifact artifact) {
        return artifact.checksum() == null ? ", and it has no published checksum; nothing was installed"
                : "; nothing was installed";
    }

    /**
     * Folders an interrupted install (Ctrl-C skips finally blocks) left, not those another jkit is
     * downloading into right now ({@link WorkDirs#inUse}); only called with the lock held.
     */
    private static List<Path> removeLeftovers(Path tmp) {
        List<Path> removed = new ArrayList<>();
        try (Stream<Path> children = Files.list(tmp)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                // remove and self-update work under the lock, so their folders here are leftovers too
                if ((name.startsWith("install-") || name.startsWith("remove-")) && !WorkDirs.inUse(child)) {
                    try {
                        Fs.deleteRecursively(child);
                        removed.add(child);
                    } catch (IOException ignored) {
                        // doctor reports what is left
                    }
                }
            }
        } catch (IOException ignored) {
            // nothing to clean
        }
        return List.copyOf(removed);
    }

    /** The source's file name, without any folder part; a fixed name if it has none. */
    static String safeFileName(String fileName, String archiveType) {
        String name = fileName == null ? "" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            return "download." + archiveType;
        }
        return name;
    }

    /** The single top folder, or macOS's Contents/Home inside it. */
    static Path locateHome(Path out) throws IOException {
        Path candidate = out;
        try (Stream<Path> children = Files.list(out)) {
            List<Path> list = children.toList();
            if (list.size() == 1 && Files.isDirectory(list.getFirst(), LinkOption.NOFOLLOW_LINKS)) {
                candidate = list.getFirst();
            }
        }
        Path macHome = candidate.resolve("Contents").resolve("Home");
        // real folders only: a Contents/Home that is a link (to "..") would install a link
        boolean real = Files.isDirectory(macHome, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(macHome.getParent(), LinkOption.NOFOLLOW_LINKS);
        if (!real && Files.isSymbolicLink(macHome)) {
            throw Archives.unsafe("its Contents/Home is a symbolic link");
        }
        Path frameworks = candidate.resolve("Contents").resolve("Frameworks");
        if (real && Files.isDirectory(frameworks, LinkOption.NOFOLLOW_LINKS)) {
            // JetBrains' JCEF build keeps Chromium in Contents/Frameworks, next to Home, and finds it
            // from java.home: install the whole bundle, with links to Home's folders at its top, so
            // JAVA_HOME/bin/java works and java still sees Contents/Home as its home
            try (Stream<Path> children = Files.list(macHome)) {
                for (Path child : children.toList()) {
                    Path link = candidate.resolve(child.getFileName().toString());
                    if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(link, Path.of("Contents", "Home", child.getFileName().toString()));
                    }
                }
            }
            return candidate;
        }
        return real ? macHome : candidate;
    }

    /**
     * Archives checks links against the archive's top folder, but only part of it may be installed
     * (macOS's Contents/Home): a link that climbs out of that part would point into tools/ once moved.
     */
    static void requireLinksInside(Path toolHome) throws IOException {
        Path root = toolHome.toAbsolutePath().normalize();
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                if (Files.isSymbolicLink(p)) {
                    Path target = Files.readSymbolicLink(p);
                    if (target.isAbsolute() || !p.getParent().resolve(target).normalize().startsWith(root)) {
                        throw Archives.unsafe("its symbolic link " + root.relativize(p) + " points outside the folder jkit installs");
                    }
                }
            }
        }
    }

    /**
     * The final move into tools/. On Windows an antivirus scan of the files just unpacked often
     * holds them for a moment and the move fails with access denied: try again for a few seconds.
     */
    private static void moveIntoPlace(Path from, Path to) throws IOException {
        boolean windows = File.separatorChar == '\\';
        for (int attempt = 1; ; attempt++) {
            try {
                WorkDirs.moveIn(() -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE), from);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                copyAcross(from, to, Fs::copyTree);
                return;
            } catch (AccessDeniedException e) {
                if (!windows || attempt >= 10) {
                    throw e;
                }
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** How {@link #copyAcross} copies a folder: {@link Fs#copyTree}, or a test's stand-in. */
    @FunctionalInterface
    interface Copy {
        void copy(Path from, Path to) throws IOException;
    }

    /**
     * tools/ is on another disk than tmp/ (a link to it): copy next to the destination, then
     * rename there, so the install still appears in one step. The copy, {@code .<name>.partial},
     * is deleted if it fails, and by Ctrl-C (WorkDirs) while it runs.
     */
    static void copyAcross(Path from, Path to, Copy copy) throws IOException {
        Path partial = to.resolveSibling("." + to.getFileName() + ".partial");
        Fs.deleteRecursively(partial);
        WorkDirs.add(partial);
        try {
            copy.copy(from, partial);
            WorkDirs.moveIn(() -> Files.move(partial, to, StandardCopyOption.ATOMIC_MOVE), from, partial);
        } catch (IOException | RuntimeException e) {
            try {
                Fs.deleteRecursively(partial);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed); // jkit prune and doctor find what is left
            }
            throw e;
        } finally {
            WorkDirs.remove(partial);
        }
        Fs.deleteRecursively(from);
    }

    /** Takes the store lock, as the commands do ({@link Store#lock()}), on the same open file. */
    private Store.Lock lock() throws IOException {
        return new Store(home).lock(home.lockFile(), "waiting for another jkit to finish installing…");
    }

    /** chmod a+x on the regular files directly in {@code bin} (not .bat/.cmd/.exe, which don't care). */
    private static void makeExecutable(Path bin) {
        makeExecutable(bin, null);
    }

    /** Like {@link #makeExecutable(Path)}, only the file {@code only} when it isn't null. */
    private static void makeExecutable(Path bin, String only) {
        try (var files = Files.list(bin)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (only != null && !n.equals(only)) {
                    continue;
                }
                if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && !n.endsWith(".bat")
                        && !n.endsWith(".cmd") && !n.endsWith(".exe")) {
                    Set<PosixFilePermission> perms =
                            new HashSet<>(Files.getPosixFilePermissions(f));
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                    perms.add(PosixFilePermission.GROUP_EXECUTE);
                    perms.add(PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(f, perms);
                }
            }
        } catch (IOException e) {
            throw new JkitException("cannot make " + bin + " executable: " + e.getMessage());
        }
    }

    /** The version check for a signed download of {@code tool}: see {@link #requireReleaseVersion}. */
    static void requireSignedVersion(Tool tool, Path toolHome, Artifact artifact) {
        switch (tool) {
            case JDK, GRAALVM -> requireReleaseVersion(toolHome, artifact);
            case MAVEN -> requireMavenVersion(toolHome, artifact);
            case SBT -> requireSbtVersion(toolHome, artifact);
            case GRADLE -> { } // Gradle publishes no signatures
        }
    }

    /**
     * For a signed JDK: the unpacked JDK's {@code release} file must report the version jkit asked
     * for, every part of it (21.0.13.1 is a fix 21.0.13 lacks; the build number after + isn't in
     * that file). Corretto 8 has no {@code release} file; its {@code version.txt} says the same thing.
     */
    static void requireReleaseVersion(Path jdkHome, Artifact artifact) {
        String found = ToolStates.releaseVersion(jdkHome);
        String compared = found == null ? null : Version.fromLegacyJava(found);
        // the parts after the third in version.txt (8.514.01.1) are Corretto's build and revision,
        // which the version jkit asked for (8.0.514) doesn't have
        int parts = Integer.MAX_VALUE;
        if (found == null) {
            found = correttoVersionTxt(jdkHome);
            compared = found == null ? null : fromCorrettoEight(found);
            parts = 3;
        }
        Version wanted = artifact.version();
        Version got = compared == null ? null : Version.parse(compared);
        boolean same = got != null && got.partCount() > 0;
        int count = got == null ? 0 : Math.min(parts, Math.max(got.partCount(), wanted.partCount()));
        for (int i = 0; same && i < count; i++) {
            same = got.part(i) == wanted.part(i);
        }
        if (!same) {
            throw new JkitException("the download for " + artifact.label() + " is signed, but it is "
                    + (found == null ? "a JDK that names its version in neither a release file nor version.txt"
                            : "JDK " + found)
                    + "; nothing was installed",
                    found == null ? "jkit can't tell which build it is: pick another version or vendor"
                            : "the source served a different build than it listed: try again later, or pick another vendor");
        }
    }

    /**
     * For a signed Maven: its {@code lib/maven-core-<version>.jar} names the release, in every
     * Maven from 3.0 to 4.
     */
    static void requireMavenVersion(Path mavenHome, Artifact artifact) {
        String found = null;
        try (Stream<Path> libs = Files.list(mavenHome.resolve("lib"))) {
            found = libs.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("maven-core-") && n.endsWith(".jar"))
                    .map(n -> n.substring("maven-core-".length(), n.length() - ".jar".length()))
                    .filter(Version::isSafeText)
                    .findFirst().orElse(null);
        } catch (IOException | UncheckedIOException e) {
            // found stays null
        }
        if (!artifact.version().raw().equals(found)) {
            throw differentBuild(artifact, found == null ? null : "Maven " + found, "lib/maven-core-*.jar");
        }
    }

    private static final Pattern SBT_VERSION =
            Pattern.compile("sbt\\.version-read\\(sbt\\.version\\)\\[([^\\]\\s]{1,64})\\]");

    /**
     * For a signed sbt: the launcher's {@code sbt/sbt.boot.properties} names the sbt version it
     * starts by default ({@code ${sbt.version-read(sbt.version)[1.10.7]}}), in every release from
     * 1.3.0 to 2.x.
     */
    static void requireSbtVersion(Path sbtHome, Artifact artifact) {
        String found = null;
        Path launcher = sbtHome.resolve("bin").resolve("sbt-launch.jar");
        if (Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
            try (ZipFile jar = new ZipFile(launcher.toFile())) {
                ZipEntry entry = jar.getEntry("sbt/sbt.boot.properties");
                if (entry != null) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        // a few hundred bytes; more is read no further
                        String text = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                        Matcher m = SBT_VERSION.matcher(text);
                        found = m.find() && Version.isSafeText(m.group(1)) ? m.group(1) : null;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // found stays null
            }
        }
        if (!artifact.version().raw().equals(found)) {
            throw differentBuild(artifact, found == null ? null : "sbt " + found, "bin/sbt-launch.jar");
        }
    }

    private static JkitException differentBuild(Artifact artifact, String found, String where) {
        return new JkitException("the download for " + artifact.label() + " is signed, but it is "
                + (found == null ? "a build that doesn't name its version in " + where : found)
                + "; nothing was installed",
                found == null ? "jkit can't tell which release it is: pick another version"
                        : "the source served a different release than it listed: try again later, or pick another version");
    }

    /** The first line of Corretto's {@code version.txt}, e.g. {@code 8.514.01.1}; null when there is none. */
    static String correttoVersionTxt(Path jdkHome) {
        Path file = jdkHome.resolve("version.txt");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024) {
                return null;
            }
            String line = Files.readString(file).lines().findFirst().orElse("").trim();
            return Version.isSafeText(line) && Version.looksLikeVersion(line) ? line : null;
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    /**
     * Corretto 8 numbers its builds {@code 8.<update>.<build>.<revision>} (8.514.01.1 is 8u514),
     * where everything else says 8.0.514; later Corretto releases already use 11.0.33.1.1.
     */
    static String fromCorrettoEight(String text) {
        Matcher m = Pattern.compile("8\\.([1-9]\\d*)((?:\\.\\d+)*)").matcher(text);
        return m.matches() ? "8.0." + m.group(1) + m.group(2) : text;
    }

    private static void requireExecutable(Tool tool, Path toolHome, String label) {
        List<String> names = switch (tool) {
            case JDK, GRAALVM -> List.of("java", "java.exe");
            case MAVEN -> List.of("mvn", "mvn.cmd");
            case GRADLE -> List.of("gradle", "gradle.bat");
            case SBT -> List.of("sbt", "sbt.bat");
        };
        for (String name : names) {
            Path launcher = toolHome.resolve("bin").resolve(name);
            if (Files.isRegularFile(launcher)) {
                if (Perms.posix() && !name.contains(".") && !Files.isExecutable(launcher)) {
                    makeExecutable(toolHome.resolve("bin")); // a zip made without Unix modes
                    // the JDK starts child processes through these: without +x, ProcessBuilder fails
                    for (String helper : List.of("jspawnhelper", "jexec")) {
                        Path file = toolHome.resolve("lib").resolve(helper);
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            makeExecutable(file.getParent(), helper);
                        }
                    }
                }
                return;
            }
        }
        throw new JkitException("unexpected archive layout for " + label + ": no bin/" + names.getFirst(),
                "nothing was installed");
    }
}
