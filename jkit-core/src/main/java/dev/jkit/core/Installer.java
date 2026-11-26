package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import dev.jkit.model.Platform;
import dev.jkit.model.Tool;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.Http;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a tool: downloads it, checks its checksum and signature, unpacks it into tmp/, checks
 * that the tool's launcher is there, and moves it into tools/ with one rename, so an interrupted
 * install leaves nothing in tools/.
 * <p>
 * The install lock is held only while looking at or changing tools/: first to see whether the
 * build is already installed and to clear what interrupted installs left in tmp/, then again for
 * the move. The download and the checks run in between, in a work folder this process owns
 * ({@link WorkDirs#create}). That way two installs in two terminals or CI jobs download side by
 * side, and remove, prune or self-update never wait for a download.
 */
public final class Installer {

    /**
     * How much checking an install insists on. jkit's commands pass {@link #REQUIRED} unless
     * --allow-unverified is given.
     */
    public enum Verification {
        /**
         * A download with no published checksum and no signature jkit can check is refused, and so
         * is one whose publisher signs its downloads with a key built into jkit when that signature
         * can't be fetched or checked: whoever can replace a download can usually replace the
         * checksum next to it too.
         */
        REQUIRED,
        /** Installs on whatever can be checked, even nothing, with a warning for each check skipped. */
        ALLOW_UNVERIFIED
    }

    /**
     * Thrown, with nothing installed, by a {@link Verification#REQUIRED} install of a download it
     * can't check: no checksum and no signature, or a signature that couldn't be fetched or checked.
     */
    public static final class VerificationRequired extends JkitException {
        VerificationRequired(String message) {
            super(message);
        }
    }

    /**
     * @param verified true when the download matched a sha256-or-stronger checksum or a good
     *                 signature by a key built into jkit; false for md5 or sha1 only, or nothing
     * @param removedLeftovers tmp/ folders of interrupted installs that this install deleted
     * @param signedBy whose OpenPGP signature was checked (Adoptium, Amazon, Apache Maven, ...), or null
     * @param notes messages for the user about the signature check: that it passed, or why it
     *              couldn't be done
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

    /**
     * @param verification what must be checkable; with {@link Verification#REQUIRED} a download
     *                     that isn't throws {@link VerificationRequired} and nothing is installed
     */
    public Result install(Artifact artifact, Http.Progress progress, Verification verification) throws IOException {
        Http.requireSecure(artifact.url());
        boolean signable = artifact.signature() != null && Signatures.covers(artifact.tool(), artifact.vendor());
        boolean required = verification == Verification.REQUIRED;
        if (required && !signable && artifact.checksum() == null) {
            throw new VerificationRequired(artifact.label() + " from " + artifact.url().getHost()
                    + " has no published checksum, so jkit can't check the download; nothing installed");
        }
        // a publisher that signs its downloads: the signature must check out
        boolean signatureRequired = required && signable;
        Tool storeTool = artifact.tool().storeTool();
        Path toolsDir = home.tools(storeTool);
        String dirName = Installed.dirName(artifact.version(), artifact.vendor(), artifact.fx());
        Path dest = toolsDir.resolve(dirName).normalize();
        // The folder's bin goes on PATH, so its name must not contain a PATH separator (: or ;), a
        // slash, spaces or control characters.
        if (!dirName.matches("[0-9A-Za-z][0-9A-Za-z.+_~-]{0,160}") || !toolsDir.equals(dest.getParent())) {
            throw new JkitException("refusing an install folder outside " + toolsDir + ": " + dest);
        }
        Installed installed = new Installed(storeTool, artifact.version(), artifact.vendor(), dest, artifact.fx());
        home.create();
        // The work folder's name says which build it is for, so that a second install of the same
        // build can tell that another jkit is downloading it.
        String prefix = "install-" + storeTool.id() + "-" + dirName + "-";
        List<Path> removed = new ArrayList<>();
        Path work = null;
        boolean waited = false;
        while (work == null) {
            try (var _ = lock()) {
                Result existing = existing(installed, List.copyOf(removed));
                if (existing != null) {
                    return existing;
                }
                Perms.mkdirs(home.tmp());
                removed.addAll(removeLeftovers(home.tmp()));
                if (!downloading(home.tmp(), prefix)) {
                    // Created under the lock, so no other jkit can take it for a leftover and delete
                    // it before this one holds it. jkit's Ctrl-C handler deletes it too.
                    work = WorkDirs.create(home.tmp(), prefix);
                }
            }
            if (work == null) {
                // Another jkit is downloading this very build: wait for it rather than download it
                // a second time, then look again. If that install fails, this one downloads.
                if (!waited) {
                    System.err.println("another jkit is installing " + artifact.label() + "; waiting for it to finish…");
                    waited = true;
                }
                pause();
            }
        }
        IOException failure = null;
        try {
            List<String> notes = new ArrayList<>();
            // Fetch the signature first. It is a few hundred bytes and can refuse the install on its
            // own (none is published, or it was made with a key jkit doesn't have), and then the user
            // doesn't wait for the whole download to hear it.
            byte[] signature = signable ? fetchSignature(artifact, work, notes, signatureRequired) : null;
            Path archive = work.resolve(safeFileName(artifact.fileName(), artifact.archiveType()));
            progress.starting();
            http.download(artifact.url(), archive, progress);
            boolean verified = false;
            if (artifact.checksum() != null && artifact.checksumAlgorithm() != null) {
                // md5 and sha1 still catch a broken download, but not a tampered one, so they don't
                // count as verified
                Checksums.verify(archive, artifact.checksumAlgorithm(), artifact.checksum(), artifact.label());
                verified = Checksums.strong(artifact.checksumAlgorithm());
            }
            String signedBy = signature != null ? checkSignature(artifact, archive, signature, notes, signatureRequired)
                    : null;
            if (artifact.signature() == null && artifact.checksum() != null
                    && Signatures.covers(artifact.tool(), artifact.vendor())) {
                // Older releases of a signing publisher (Temurin before mid-2022, Maven before 3.9.0)
                // come unsigned. Say so, so the user knows that only the checksum was checked.
                notes.add("note: no signature jkit can check is published for " + artifact.label()
                        + "; only its checksum was checked");
            } else if (signedBy == null && notes.isEmpty() && artifact.checksum() != null
                    && artifact.checksumAlgorithm() != null) {
                // a publisher that doesn't sign (Zulu, Gradle, GraalVM CE): say what was checked,
                // as "signature checked" does for the ones that do
                notes.add("checksum checked (" + artifact.checksumAlgorithm().toLowerCase(Locale.ROOT)
                        .replace("-", "") + ")");
            }
            // A good signature by a key built into jkit is a stronger check than any checksum. A
            // signature that is published but couldn't be checked (--allow-unverified) leaves the
            // install unverified even with a strong checksum, because whoever can replace the
            // download can usually replace the checksum next to it too.
            verified = signedBy != null || (verified && !signable);
            // Created here, without its parents, rather than by extract: if Ctrl-C has already
            // deleted the work folder, this fails instead of creating it again.
            Path out = Files.createDirectory(work.resolve("out"));
            try {
                Archives.extract(archive, out, artifact.archiveType());
            } catch (IOException e) {
                // Anything but a file system error means the archive is truncated or corrupt (EOF,
                // "not a ZIP archive"), so the message blames the download, not this disk.
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
                // A signature covers the file's bytes, not its name: any build the publisher ever
                // signed would pass, an older one included, and the checksum may come from the same
                // server as the download. So the unpacked tool must also be the version asked for.
                requireSignedVersion(storeTool, toolHome, artifact);
            }
            Store.writeMarker(toolHome, installed, artifact.url().toString(), verified, signedBy);
            try (var _ = lock()) {
                // Another jkit may have installed the same build in the meantime: keep that one.
                Result existing = existing(installed, removed);
                if (existing != null) {
                    return existing;
                }
                Perms.mkdirs(dest.getParent());
                moveIntoPlace(toolHome, dest);
            }
            return new Result(installed, true, verified, removed, signedBy, List.copyOf(notes));
        } catch (IOException e) {
            failure = e; // so a failed cleanup below is attached to it
            throw e;
        } finally {
            WorkDirs.remove(work); // releases its lock first, since Windows can't delete an open file
            try {
                Fs.deleteRecursively(work);
            } catch (IOException e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                }
                // The next install removes what is left in tmp/; don't let this hide the real result.
            }
        }
    }

    /**
     * Whether a running jkit (another one, or another thread of this one) is downloading into a
     * work folder named {@code prefix...}: the same build. Only called with the lock held.
     */
    private static boolean downloading(Path tmp, String prefix) {
        try (Stream<Path> children = Files.list(tmp)) {
            return children.anyMatch(c -> c.getFileName().toString().startsWith(prefix)
                    && WorkDirs.use(c) == WorkDirs.Use.IN_USE);
        } catch (IOException e) {
            return false;
        }
    }

    /** How long an install waiting for another jkit's install of the same build sleeps between looks. */
    static long pauseMillis = 500;

    private static void pause() throws IOException {
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for another jkit");
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
     * Fetches the publisher's signature and checks what it can before the download: who made it,
     * and whether it is a kind jkit accepts. Returns the signature when only the download can
     * decide. When it can't be fetched or checked, this throws if {@code required}; otherwise it
     * adds a warning to {@code notes} and returns null, leaving the checksum as the only check.
     */
    private byte[] fetchSignature(Artifact artifact, Path work, List<String> notes, boolean required)
            throws IOException {
        Path file = work.resolve("download.sig");
        byte[] bytes;
        try {
            // Capped, so a signature link that serves a JDK's worth of data isn't read whole.
            http.download(artifact.signature(), file, Http.Progress.NONE, Signatures.MAX_SIGNATURE);
            bytes = Files.readAllBytes(file);
        } catch (IOException | JkitException e) {
            String why = "couldn't fetch the signature of " + artifact.label() + " ("
                    + Visible.of(String.valueOf(e.getMessage())) + ")";
            if (required) {
                throw new VerificationRequired(why + refused(artifact));
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

    /** For a signature jkit can't check: throws when {@code required}, else adds a warning to {@code notes}. */
    private static void uncheckable(Artifact artifact, Signatures.Result result, List<String> notes, boolean required) {
        String why = "couldn't check the signature of " + artifact.label() + ": " + result.detail();
        // An unknown key may be the publisher's new one, which a newer jkit knows. A retired key
        // never comes back, so the advice there is a newer build instead.
        String newer = result.outcome() == Signatures.Outcome.UNKNOWN_KEY
                ? " (a newer jkit may know a new key: jkit self-update)"
                : result.outcome() == Signatures.Outcome.RETIRED_KEY ? " (pick a newer build)" : "";
        if (required) {
            throw new VerificationRequired(why + newer + refused(artifact));
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
     * Deletes the folders that interrupted installs left in tmp/ (Ctrl-C skips finally blocks), but
     * not those another jkit is downloading into right now ({@link WorkDirs#inUse}). Only called
     * with the lock held.
     */
    private static List<Path> removeLeftovers(Path tmp) {
        List<Path> removed = new ArrayList<>();
        try (Stream<Path> children = Files.list(tmp)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                // remove does its work while holding the lock, so a remove- folder seen while this
                // install holds it is a leftover too
                if ((name.startsWith("install-") || name.startsWith("remove-")) && !WorkDirs.inUse(child)) {
                    try {
                        Fs.deleteRecursively(child);
                        removed.add(child);
                    } catch (IOException ignored) {
                        // doctor reports whatever couldn't be deleted
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

    /** The tool's home in the unpacked archive: its single top folder, or macOS's Contents/Home in it. */
    static Path locateHome(Path out) throws IOException {
        Path candidate = out;
        try (Stream<Path> children = Files.list(out)) {
            List<Path> list = children.toList();
            if (list.size() == 1 && Files.isDirectory(list.getFirst(), LinkOption.NOFOLLOW_LINKS)) {
                candidate = list.getFirst();
            }
        }
        Path macHome = candidate.resolve("Contents").resolve("Home");
        // Only real folders count: a Contents/Home that is a symbolic link (to "..", say) would
        // install a link.
        boolean real = Files.isDirectory(macHome, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(macHome.getParent(), LinkOption.NOFOLLOW_LINKS);
        if (!real && Files.isSymbolicLink(macHome)) {
            throw Archives.unsafe("its Contents/Home is a symbolic link");
        }
        Path frameworks = candidate.resolve("Contents").resolve("Frameworks");
        if (real && Files.isDirectory(frameworks, LinkOption.NOFOLLOW_LINKS)) {
            // JetBrains' JCEF builds keep Chromium in Contents/Frameworks, next to Home, and find it
            // relative to java.home. So the whole bundle is installed, with links at its top to
            // Home's folders: JAVA_HOME/bin/java works, and java still sees Contents/Home as its home.
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
     * Archives checks that links stay inside the archive's top folder, but jkit may install only
     * part of it (macOS's Contents/Home). A link that climbs out of that part would point somewhere
     * else in tools/ once moved, so it is refused here.
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
        boolean windows = Platform.hostIsWindows();
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
     * For when tools/ is on another disk than tmp/ (tools/ is a link), so no rename can move the
     * install. Copies it next to the destination as {@code .<name>.partial} and renames that, so the
     * install still appears in one step. The partial copy is deleted if the copy fails, and by
     * Ctrl-C (through WorkDirs) while it runs.
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
                e.addSuppressed(suppressed); // jkit prune and doctor find whatever is left
            }
            throw e;
        } finally {
            WorkDirs.remove(partial);
        }
        Fs.deleteRecursively(from);
    }

    /**
     * Takes the install lock through {@link Store#lock()}, as the commands do, so the process opens
     * the lock file only once however many code paths hold it.
     */
    private Store.Lock lock() throws IOException {
        return new Store(home).lock(home.lockFile(), "waiting for another jkit to finish installing…");
    }

    /** Runs chmod a+x on the regular files directly in {@code bin}, except .bat, .cmd and .exe files. */
    private static void makeExecutable(Path bin) {
        try (var files = Files.list(bin)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".bat") && !n.endsWith(".cmd") && !n.endsWith(".exe")) {
                    makeFileExecutable(f);
                }
            }
        } catch (IOException e) {
            throw new JkitException("cannot make " + bin + " executable: " + e.getMessage());
        }
    }

    /** Runs chmod a+x on {@code file} when it is a regular file. */
    private static void makeFileExecutable(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException e) {
            throw new JkitException("cannot make " + file + " executable: " + e.getMessage());
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
     * For a signed JDK, checks that the unpacked JDK's {@code release} file reports every part of
     * the version jkit asked for, since 21.0.13.1 has a fix that 21.0.13 lacks. (The build number
     * after + isn't in that file.) Corretto 8 has no {@code release} file, so its
     * {@code version.txt} is read instead.
     */
    static void requireReleaseVersion(Path jdkHome, Artifact artifact) {
        String found = ToolStates.releaseVersion(jdkHome);
        String compared = found == null ? null : Version.fromLegacyJava(found);
        // In version.txt (8.514.01.1) the parts after the third are Corretto's build and revision,
        // which the version jkit asked for (8.0.514) doesn't have, so only three parts are compared.
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
     * For a signed Maven, checks the release named by its {@code lib/maven-core-<version>.jar},
     * which every Maven from 3.0 to 4 has.
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
     * For a signed sbt, checks the version the launcher starts by default, which its
     * {@code sbt/sbt.boot.properties} names ({@code ${sbt.version-read(sbt.version)[1.10.7]}}) in
     * every release from 1.3.0 to 2.x.
     */
    static void requireSbtVersion(Path sbtHome, Artifact artifact) {
        String found = null;
        Path launcher = sbtHome.resolve("bin").resolve("sbt-launch.jar");
        if (Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
            try (ZipFile jar = new ZipFile(launcher.toFile())) {
                ZipEntry entry = jar.getEntry("sbt/sbt.boot.properties");
                if (entry != null) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        // The file is a few hundred bytes; a bigger one is read only this far.
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

    private static final Pattern CORRETTO_EIGHT = Pattern.compile("8\\.([1-9]\\d*)((?:\\.\\d+)*)");

    /**
     * Corretto 8 numbers its builds {@code 8.<update>.<build>.<revision>} (8.514.01.1 is 8u514),
     * where everything else says 8.0.514; later Corretto releases already use 11.0.33.1.1.
     */
    static String fromCorrettoEight(String text) {
        Matcher m = CORRETTO_EIGHT.matcher(text);
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
                    makeExecutable(toolHome.resolve("bin")); // a zip archive made without Unix file modes
                    // The JDK starts child processes through these, so without +x ProcessBuilder fails.
                    for (String helper : List.of("jspawnhelper", "jexec")) {
                        makeFileExecutable(toolHome.resolve("lib").resolve(helper));
                    }
                }
                return;
            }
        }
        throw new JkitException("unexpected archive layout for " + label + ": no bin/" + names.getFirst(),
                "nothing was installed");
    }
}
