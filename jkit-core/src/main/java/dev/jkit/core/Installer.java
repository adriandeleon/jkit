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

    /** {@code verified} is false when the source published no checksum. */
    public record Result(Installed installed, boolean fresh, boolean verified) { }

    private final JkitHome home;
    private final Http http;

    public Installer(JkitHome home, Http http) {
        this.home = home;
        this.http = http;
    }

    @SuppressWarnings("try") // the lock is held for the whole block
    public Result install(Artifact artifact, Http.Progress progress) throws IOException {
        Tool storeTool = artifact.tool().storeTool();
        Path dest = home.tools(storeTool).resolve(Installed.dirName(artifact.version(), artifact.vendor(), artifact.fx()));
        Installed installed = new Installed(storeTool, artifact.version(), artifact.vendor(), dest, artifact.fx());
        Files.createDirectories(home.root());
        try (FileChannel channel = FileChannel.open(home.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.isDirectory(dest)) {
                return new Result(installed, false, true);
            }
            Files.createDirectories(home.tmp());
            Path work = Files.createTempDirectory(home.tmp(), "install-");
            try {
                Path archive = work.resolve(artifact.fileName());
                http.download(artifact.url(), archive, progress);
                boolean verified = artifact.checksum() != null;
                if (verified) {
                    Checksums.verify(archive, artifact.checksumAlgorithm(), artifact.checksum(), artifact.label());
                }
                Path out = work.resolve("out");
                Archives.extract(archive, out, artifact.archiveType());
                Path toolHome = locateHome(out);
                requireExecutable(storeTool, toolHome, artifact.label());
                Store.writeMarker(toolHome, installed, artifact.url().toString());
                Files.createDirectories(dest.getParent());
                Files.move(toolHome, dest, StandardCopyOption.ATOMIC_MOVE);
                return new Result(installed, true, verified);
            } finally {
                Fs.deleteRecursively(work);
            }
        }
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
