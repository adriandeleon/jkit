package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.Http;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Download, check checksum, unpack into tmp/, check the tool's executable, then move into
 * tools/ in one rename. An interrupted install leaves nothing in tools/.
 */
public final class Installer {

    /**
     * @param verified false when the source published no checksum, or only an md5 or sha1 one
     * @param removedLeftovers tmp/ folders of interrupted installs that this install deleted
     */
    public record Result(Installed installed, boolean fresh, boolean verified, List<Path> removedLeftovers) { }

    private final JkitHome home;
    private final Http http;

    public Installer(JkitHome home, Http http) {
        this.home = home;
        this.http = http;
    }

    @SuppressWarnings("try") // the lock is held for the whole block
    public Result install(Artifact artifact, Http.Progress progress) throws IOException {
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
        try (FileChannel channel = FileChannel.open(home.lockFile(),
                java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), dev.jkit.model.Perms.file());
             FileLock ignored = lock(channel)) {
            if (Files.isDirectory(dest)) {
                if (!Files.isRegularFile(dest.resolve(Store.MARKER), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new JkitException(dest + " exists but isn't a jkit install (no " + Store.MARKER + ")",
                            "move or delete that folder, then install again");
                }
                return new Result(installed, false, Store.verified(dest), List.of());
            }
            dev.jkit.model.Perms.mkdirs(home.tmp());
            List<Path> removed = removeLeftovers(home.tmp());
            Path work = Files.createTempDirectory(home.tmp(), "install-");
            IOException failure = null;
            try {
                Path archive = work.resolve(safeFileName(artifact.fileName(), artifact.archiveType()));
                http.download(artifact.url(), archive, progress);
                boolean verified = false;
                if (artifact.checksum() != null && artifact.checksumAlgorithm() != null) {
                    // md5 and sha1 still catch a broken download, but don't count as verified
                    Checksums.verify(archive, artifact.checksumAlgorithm(), artifact.checksum(), artifact.label());
                    verified = Checksums.strong(artifact.checksumAlgorithm());
                }
                Path out = work.resolve("out");
                try {
                    Archives.extract(archive, out, artifact.archiveType());
                } catch (IOException e) {
                    // truncated or corrupt (EOF, "not a ZIP archive"): the download, not this disk
                    if (e instanceof java.nio.file.FileSystemException) {
                        throw e;
                    }
                    throw new JkitException("the downloaded archive for " + artifact.label() + " is damaged ("
                            + dev.jkit.model.Visible.of(String.valueOf(e.getMessage())) + "); nothing was installed",
                            "try again; if it keeps happening, the source has a broken file: pick another version");
                }
                Path toolHome = locateHome(out);
                requireLinksInside(toolHome);
                requireExecutable(storeTool, toolHome, artifact.label());
                Store.writeMarker(toolHome, installed, artifact.url().toString(), verified);
                dev.jkit.model.Perms.mkdirs(dest.getParent());
                moveIntoPlace(toolHome, dest);
                return new Result(installed, true, verified, removed);
            } catch (IOException | RuntimeException e) {
                if (e instanceof IOException io) {
                    failure = io;
                }
                throw e;
            } finally {
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
    }

    /** Folders an interrupted install (Ctrl-C skips finally blocks) left; only called with the lock held. */
    private static List<Path> removeLeftovers(Path tmp) {
        List<Path> removed = new java.util.ArrayList<>();
        try (Stream<Path> children = Files.list(tmp)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                // remove also works under the lock, so a remove- folder here is a leftover too
                if (name.startsWith("install-") || name.startsWith("remove-")) {
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
            if (list.size() == 1 && Files.isDirectory(list.getFirst(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                candidate = list.getFirst();
            }
        }
        Path macHome = candidate.resolve("Contents").resolve("Home");
        // real folders only: a Contents/Home that is a link (to "..") would install a link
        boolean real = Files.isDirectory(macHome, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(macHome.getParent(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!real && Files.isSymbolicLink(macHome)) {
            throw Archives.unsafe("its Contents/Home is a symbolic link");
        }
        Path frameworks = candidate.resolve("Contents").resolve("Frameworks");
        if (real && Files.isDirectory(frameworks, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            // JetBrains' JCEF build keeps Chromium in Contents/Frameworks, next to Home, and finds it
            // from java.home: install the whole bundle, with links to Home's folders at its top, so
            // JAVA_HOME/bin/java works and java still sees Contents/Home as its home
            try (Stream<Path> children = Files.list(macHome)) {
                for (Path child : children.toList()) {
                    Path link = candidate.resolve(child.getFileName().toString());
                    if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
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

    private static final boolean POSIX =
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * The final move into tools/. On Windows an antivirus scan of the files just unpacked often
     * holds them for a moment and the move fails with access denied: try again for a few seconds.
     */
    private static void moveIntoPlace(Path from, Path to) throws IOException {
        boolean windows = java.io.File.separatorChar == '\\';
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // tools/ is on another disk than tmp/ (a link to it): copy next to the destination,
                // then rename there, so the install still appears in one step
                Path partial = to.resolveSibling("." + to.getFileName() + ".partial");
                Fs.deleteRecursively(partial);
                Fs.copyTree(from, partial);
                Files.move(partial, to, StandardCopyOption.ATOMIC_MOVE);
                Fs.deleteRecursively(from);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
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

    /** The store lock; says so on stderr when another jkit holds it, instead of hanging silently. */
    private static FileLock lock(FileChannel channel) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock != null) {
            return lock;
        }
        System.err.println("waiting for another jkit to finish installing…");
        return channel.lock();
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
                if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !n.endsWith(".bat")
                        && !n.endsWith(".cmd") && !n.endsWith(".exe")) {
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                            new java.util.HashSet<>(Files.getPosixFilePermissions(f));
                    perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                    perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
                    perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(f, perms);
                }
            }
        } catch (IOException e) {
            throw new JkitException("cannot make " + bin + " executable: " + e.getMessage());
        }
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
                if (POSIX && !name.contains(".") && !Files.isExecutable(launcher)) {
                    makeExecutable(toolHome.resolve("bin")); // a zip made without Unix modes
                    // the JDK starts child processes through these: without +x, ProcessBuilder fails
                    for (String helper : List.of("jspawnhelper", "jexec")) {
                        Path file = toolHome.resolve("lib").resolve(helper);
                        if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
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
