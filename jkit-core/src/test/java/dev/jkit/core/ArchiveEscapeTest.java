package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Archives that try to write or read outside the install folder through links. */
@DisabledOnOs(OS.WINDOWS) // symlinks need Developer Mode there
class ArchiveEscapeTest {
    @TempDir
    Path tmp;

    @FunctionalInterface
    interface Entries {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private Path tar(Entries entries) throws IOException {
        Path archive = tmp.resolve("evil.tar.gz");
        try (OutputStream raw = Files.newOutputStream(archive);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(raw))) {
            entries.write(tar);
        }
        return archive;
    }

    private static void symlink(TarArchiveOutputStream tar, String name, String target) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
        e.setLinkName(target);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    private static void hardlink(TarArchiveOutputStream tar, String name, String target) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_LINK);
        e.setLinkName(target);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    private static void file(TarArchiveOutputStream tar, String name, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(data.length);
        e.setMode(0644);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    @Test
    void writingThroughAnEscapingSymlinkIsRefused() throws IOException {
        Path victim = Files.createDirectories(tmp.resolve("victim"));
        Path archive = tar(t -> {
            symlink(t, "jdk/esc", "../../victim");
            file(t, "jdk/esc/owned.txt", "x");
        });
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("run/out"), "tar.gz"))
                .hasMessageContaining("symlink");
        assertThat(victim.resolve("owned.txt")).doesNotExist();
    }

    @Test
    void writingThroughAnInnerSymlinkIsRefusedToo() throws IOException {
        // even a link that stays inside could be swapped for a folder written through; refuse the pattern
        Path archive = tar(t -> {
            symlink(t, "jdk/alias", "lib");
            file(t, "jdk/alias/x.txt", "x");
        });
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "tar.gz"))
                .hasMessageContaining("through a symlink");
    }

    @Test
    void absoluteSymlinkIsRefused() throws IOException {
        Path archive = tar(t -> symlink(t, "jdk/passwd", "/etc/passwd"));
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "tar.gz"))
                .hasMessageContaining("points outside");
    }

    @Test
    void hardLinkThroughASymlinkCannotReadOutside() throws IOException {
        Files.writeString(Files.createDirectories(tmp.resolve("secret")).resolve("key"), "s3cret");
        Path archive = tar(t -> {
            symlink(t, "jdk/s", "../../secret");
            hardlink(t, "jdk/copy", "jdk/s/key");
        });
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("run/out"), "tar.gz"))
                .isInstanceOf(IOException.class);
        assertThat(tmp.resolve("run/out/jdk/copy")).doesNotExist();
    }

    @Test
    void hardLinkToAnEarlierFileWorks() throws IOException {
        Path archive = tar(t -> {
            file(t, "jdk/lib/a", "data");
            hardlink(t, "jdk/lib/b", "jdk/lib/a");
            symlink(t, "jdk/legal/LICENSE", "../lib/a");
        });
        Path out = tmp.resolve("out");
        Archives.extract(archive, out, "tar.gz");
        assertThat(out.resolve("jdk/lib/b")).hasContent("data");
        assertThat(Files.readSymbolicLink(out.resolve("jdk/legal/LICENSE"))).isEqualTo(Path.of("../lib/a"));
    }

    @Test
    void zipSymlinkOutsideIsRefused() throws IOException {
        Path archive = tmp.resolve("evil.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry e = new ZipArchiveEntry("maven/esc");
            e.setUnixMode(UnixStat.LINK_FLAG | 0777);
            zip.putArchiveEntry(e);
            zip.write("../../victim".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("run/out"), "zip"))
                .hasMessageContaining("points outside");
    }

    @Test
    void nonNormalizedTargetStillWorks() throws IOException {
        Path out = tmp.resolve("x/../out2");
        Archives.extract(ArchivesTest.jdkTarGz(tmp, "jdk-21"), out, "tar.gz");
        assertThat(tmp.resolve("out2/jdk-21/bin/java")).exists();
    }
}
