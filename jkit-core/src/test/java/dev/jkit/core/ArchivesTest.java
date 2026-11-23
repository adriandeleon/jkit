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
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // Unix modes
    void filesAreNeverWritableByEveryone() throws IOException {
        Path archive = tmp.resolve("loose.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry e = new ZipArchiveEntry("apache-maven/bin/mvn");
            e.setUnixMode(0100777);
            zip.putArchiveEntry(e);
            zip.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        Path out = tmp.resolve("out");
        Archives.extract(archive, out, "zip");
        assertThat(Files.getPosixFilePermissions(out.resolve("apache-maven/bin/mvn")))
                .contains(PosixFilePermission.OWNER_EXECUTE).doesNotContain(PosixFilePermission.OTHERS_WRITE);
    }

    /** A raw tar header block with a valid checksum. */
    private static byte[] header(String name, char type, long size) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, h, 0, n.length);
        byte[] mode = "0000644\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(mode, 0, h, 100, mode.length);
        byte[] sz = String.format("%011o\0", size).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sz, 0, h, 124, sz.length);
        h[156] = (byte) type;
        java.util.Arrays.fill(h, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        byte[] ck = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ck, 0, h, 148, ck.length);
        return h;
    }

    @Test
    void aHugeLongNameHeaderIsRefusedBeforeItIsRead() throws IOException {
        // a GNU long-name header claiming 1 GiB in a tiny file: commons-compress would allocate it
        Path archive = tmp.resolve("longname.tar.gz");
        try (OutputStream out = new GzipCompressorOutputStream(Files.newOutputStream(archive))) {
            out.write(header("././@LongLink", 'L', 1L << 30));
            out.write(new byte[4096]);
        }
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "tar.gz"))
                .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("claims");
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // symlinks
    void aMacHomeThatIsALinkIsRefused() throws IOException {
        Path out = Files.createDirectories(tmp.resolve("out/jdk-21/Contents"));
        Files.createSymbolicLink(out.resolve("Home"), Path.of(".."));
        assertThatThrownBy(() -> Installer.locateHome(tmp.resolve("out")))
                .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("symbolic link");
    }

    @Test
    void anArchiveWithTooManyEntriesIsRefused() throws IOException {
        Path archive = tmp.resolve("many.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            for (int i = 0; i < 11; i++) {
                zip.putArchiveEntry(new ZipArchiveEntry("x/d" + i + "/"));
                zip.closeArchiveEntry();
            }
        }
        Archives.entryLimit = 10;
        try {
            assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip"))
                    .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("more than 10 entries");
        } finally {
            Archives.entryLimit = Archives.MAX_ENTRIES;
        }
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
