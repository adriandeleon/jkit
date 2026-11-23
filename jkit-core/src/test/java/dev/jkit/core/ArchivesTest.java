package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivesTest {
    @TempDir
    Path tmp;

    /** A tar.gz with an executable bin/java, a lib/ file and a symlink, like a real JDK. */
    static Path jdkTarGz(Path dir, String top) throws IOException {
        Path archive = dir.resolve("jdk.tar.gz");
        try (OutputStream raw = Files.newOutputStream(archive);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(raw))) {
            file(tar, top + "/bin/java", "#!/bin/sh\necho fake java\n", 0755);
            file(tar, top + "/lib/modules", "data", 0644);
            file(tar, top + "/release", "JAVA_VERSION=\"21.0.13\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n", 0644);
            TarArchiveEntry link = new TarArchiveEntry(top + "/bin/javac-link", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("java");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        return archive;
    }

    private static void file(TarArchiveOutputStream tar, String name, String text, int mode) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(data.length);
        e.setMode(mode);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // Unix modes, symlinks
    void tarGzKeepsPermissionsAndSymlinks() throws IOException {
        Path out = tmp.resolve("out");
        Archives.extract(jdkTarGz(tmp, "jdk-21"), out, "tar.gz");
        Path java = out.resolve("jdk-21/bin/java");
        assertThat(Files.getPosixFilePermissions(java)).contains(PosixFilePermission.OWNER_EXECUTE);
        assertThat(Files.getPosixFilePermissions(out.resolve("jdk-21/lib/modules")))
                .doesNotContain(PosixFilePermission.OWNER_EXECUTE);
        Path link = out.resolve("jdk-21/bin/javac-link");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(Path.of("java"));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // Unix modes, symlinks
    void zipKeepsUnixModes() throws IOException {
        Path archive = tmp.resolve("maven.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry e = new ZipArchiveEntry("apache-maven/bin/mvn");
            e.setUnixMode(0100755);
            zip.putArchiveEntry(e);
            zip.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        Path out = tmp.resolve("out");
        Archives.extract(archive, out, "zip");
        assertThat(Files.getPosixFilePermissions(out.resolve("apache-maven/bin/mvn")))
                .contains(PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    void refusesEntriesOutsideTheTarget() throws IOException {
        Path archive = tmp.resolve("evil.tar.gz");
        try (OutputStream raw = Files.newOutputStream(archive);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(raw))) {
            file(tar, "../escape.txt", "x", 0644);
        }
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "tar.gz"))
                .hasMessageContaining("escapes");
        assertThat(tmp.resolve("escape.txt")).doesNotExist();
    }
}
