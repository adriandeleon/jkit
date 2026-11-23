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

    private Path tarOf(String name, byte[]... blocks) throws IOException {
        Path archive = tmp.resolve(name);
        try (OutputStream out = new GzipCompressorOutputStream(Files.newOutputStream(archive))) {
            for (byte[] b : blocks) {
                out.write(b);
                if (b.length % 512 != 0) {
                    out.write(new byte[512 - b.length % 512]);
                }
            }
            out.write(new byte[1024]);
        }
        return archive;
    }

    private static byte[] pax(String... records) {
        StringBuilder sb = new StringBuilder();
        for (String r : records) {
            int len = r.length() + 2;
            len += String.valueOf(len + String.valueOf(len).length()).length();
            sb.append(len).append(' ').append(r).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void headerTypesJkitDoesNotUnpackAreRefused() throws IOException {
        // Solaris X headers and sparse files: commons-compress would read them, the scan won't guess
        Path solaris = tarOf("x.tar.gz", header("h", 'X', 10), new byte[10]);
        assertThatThrownBy(() -> Archives.extract(solaris, tmp.resolve("o1"), "tar.gz"))
                .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("type 'X'");
        byte[] sparse = pax("GNU.sparse.major=1");
        Path gnu = tarOf("s.tar.gz", header("h", 'x', sparse.length), sparse, header("f", '0', 0));
        assertThatThrownBy(() -> Archives.extract(gnu, tmp.resolve("o2"), "tar.gz"))
                .hasMessageContaining("sparse");
        byte[] global = pax("size=0");
        Path g = tarOf("g.tar.gz", header("h", 'g', global.length), global, header("f", '0', 0));
        assertThatThrownBy(() -> Archives.extract(g, tmp.resolve("o3"), "tar.gz"))
                .hasMessageContaining("global PAX header sets a size");
    }

    @Test
    void aFolderEntryThatCarriesDataIsRefused() throws IOException {
        // commons-compress never reads a folder's data, so it parses that data as more headers: a huge
        // long-name header hidden there would get past a scan that skipped it
        byte[] hidden = header("././@LongLink", 'L', 1L << 30);
        Path typed = tarOf("d5.tar.gz", header("d/", '5', 512), hidden);
        assertThatThrownBy(() -> Archives.extract(typed, tmp.resolve("o1"), "tar.gz"))
                .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("folder entry");
        Path slash = tarOf("d0.tar.gz", header("d/", '0', 512), hidden);
        assertThatThrownBy(() -> Archives.extract(slash, tmp.resolve("o2"), "tar.gz"))
                .hasMessageContaining("folder entry");
        byte[] path = pax("path=d/", "size=512");
        Path viaPax = tarOf("dx.tar.gz", header("h", 'x', path.length), path, header("f", '0', 0), hidden);
        assertThatThrownBy(() -> Archives.extract(viaPax, tmp.resolve("o3"), "tar.gz"))
                .hasMessageContaining("folder entry");
        byte[] name = "d/\0".getBytes(StandardCharsets.US_ASCII);
        Path viaLong = tarOf("dl.tar.gz", header("././@LongLink", 'L', name.length), name, header("f", '0', 512),
                hidden);
        assertThatThrownBy(() -> Archives.extract(viaLong, tmp.resolve("o4"), "tar.gz"))
                .hasMessageContaining("folder entry");
        // an empty folder entry is fine
        Archives.extract(tarOf("ok.tar.gz", header("d/", '5', 0), header("d/f", '0', 0)), tmp.resolve("o5"), "tar.gz");
        assertThat(tmp.resolve("o5/d")).isDirectory();
    }

    @Test
    void aZipIsCountedByItsHeadersNotItsEndRecord() throws IOException {
        Path archive = tmp.resolve("lying.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            for (int i = 0; i < 11; i++) {
                zip.putArchiveEntry(new ZipArchiveEntry("x/d" + i + "/"));
                zip.closeArchiveEntry();
            }
        }
        // rewrite the end record's two entry counts to claim 1
        byte[] bytes = Files.readAllBytes(archive);
        for (int i = bytes.length - 22; i >= 0; i--) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 5 && bytes[i + 3] == 6) {
                bytes[i + 8] = 1;
                bytes[i + 9] = 0;
                bytes[i + 10] = 1;
                bytes[i + 11] = 0;
                break;
            }
        }
        Files.write(archive, bytes);
        Archives.entryLimit = 10;
        try {
            assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip"))
                    .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("more than 10 entries");
        } finally {
            Archives.entryLimit = Archives.MAX_ENTRIES;
        }
    }

    @Test
    void theLastPaxSizeIsTheOneCounted() throws IOException {
        // commons-compress uses the last size record: the scan must count that one, not the first
        byte[] two = pax("size=0", "size=5000000000");
        Path archive = tarOf("p.tar.gz", header("h", 'x', two.length), two, header("f", '0', 0));
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("o"), "tar.gz"))
                .hasMessageContaining("more than 3 GB");
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // Unix modes
    void aZipSymlinkTargetIsNeverReadWhole() throws IOException {
        Path archive = tmp.resolve("link.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry e = new ZipArchiveEntry("top/link");
            e.setUnixMode(0120777);
            zip.putArchiveEntry(e);
            zip.write(new byte[10_000]);
            zip.closeArchiveEntry();
        }
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip"))
                .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("symlink entry");
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
