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
        Path dest = toolsDir.resolve(Installed.dirName(artifact.version(), artifact.vendor(), artifact.fx())).normalize();
        if (!toolsDir.equals(dest.getParent())) {
            throw new JkitException("refusing an install folder outside " + toolsDir + ": " + dest);
        }
        Installed installed = new Installed(storeTool, artifact.version(), artifact.vendor(), dest, artifact.fx());
        Files.createDirectories(home.root());
        try (FileChannel channel = FileChannel.open(home.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.isDirectory(dest)) {
                if (!Files.isRegularFile(dest.resolve(Store.MARKER))) {
                    throw new JkitException(dest + " exists but isn't a jkit install (no " + Store.MARKER + ")",
                            "move or delete that folder, then install again");
                }
                return new Result(installed, false, Store.verified(dest), List.of());
            }
            Files.createDirectories(home.tmp());
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
                Archives.extract(archive, out, artifact.archiveType());
                Path toolHome = locateHome(out);
                requireExecutable(storeTool, toolHome, artifact.label());
                Store.writeMarker(toolHome, installed, artifact.url().toString(), verified);
                Files.createDirectories(dest.getParent());
                Files.move(toolHome, dest, StandardCopyOption.ATOMIC_MOVE);
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
                if (child.getFileName().toString().startsWith("install-")) {
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
            if (list.size() == 1 && Files.isDirectory(list.getFirst())) {
                candidate = list.getFirst();
            }
        }
        Path macHome = candidate.resolve("Contents").resolve("Home");
        return Files.isDirectory(macHome) ? macHome : candidate;
    }

    private static void requireExecutable(Tool tool, Path toolHome, String label) {
        List<String> names = switch (tool) {
            case JDK, GRAALVM -> List.of("java", "java.exe");
            case MAVEN -> List.of("mvn", "mvn.cmd");
            case GRADLE -> List.of("gradle", "gradle.bat");
            case SBT -> List.of("sbt", "sbt.bat");
        };
        for (String name : names) {
            if (Files.isRegularFile(toolHome.resolve("bin").resolve(name))) {
                return;
            }
        }
        throw new JkitException("unexpected archive layout for " + label + ": no bin/" + names.getFirst(),
                "nothing was installed");
    }
}
