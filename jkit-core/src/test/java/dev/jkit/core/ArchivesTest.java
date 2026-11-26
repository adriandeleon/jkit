package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    @DisabledOnOs(OS.WINDOWS) // Unix modes, symlinks
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
    @DisabledOnOs(OS.WINDOWS) // Unix modes, symlinks
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
    @DisabledOnOs(OS.WINDOWS) // Unix modes
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

    @Test
    @DisabledOnOs(OS.WINDOWS) // Unix modes
    void filesAndFoldersAreNeverWritableByTheGroup() throws IOException {
        Path archive = tmp.resolve("group.tar.gz");
        try (OutputStream raw = Files.newOutputStream(archive);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(raw))) {
            TarArchiveEntry dir = new TarArchiveEntry("jdk/lib/");
            dir.setMode(040775);
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
            file(tar, "jdk/bin/java", "#!/bin/sh\n", 0775);
            file(tar, "jdk/lib/modules", "data", 0664);
        }
        Path out = tmp.resolve("out");
        Archives.extract(archive, out, "tar.gz");
        assertThat(Files.getPosixFilePermissions(out.resolve("jdk/bin/java")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.getPosixFilePermissions(out.resolve("jdk/lib/modules")))
                .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        for (Path dir : new Path[] {out.resolve("jdk"), out.resolve("jdk/lib")}) {
            assertThat(Files.getPosixFilePermissions(dir))
                    .doesNotContain(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // a NUL is the one byte Linux refuses
    void anEntryNameThisSystemCantHoldIsUnsafeNotACrash() throws IOException {
        Path archive = tmp.resolve("nul.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            zip.putArchiveEntry(new ZipArchiveEntry("jdk/bin/ja\0va"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip"))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("unsafe to unpack (an entry name this system can't use: jdk/bin/ja\\x00va)");

        Path linked = tmp.resolve("link.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(linked)) {
            ZipArchiveEntry link = new ZipArchiveEntry("jdk/bin/javac");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write("ja\0va".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        assertThatThrownBy(() -> Archives.extract(linked, tmp.resolve("out2"), "zip"))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("an entry name this system can't use: jdk/bin/javac");
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
        Arrays.fill(h, 148, 156, (byte) ' ');
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
                .isInstanceOf(JkitException.class).hasMessageContaining("claims");
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
                .isInstanceOf(JkitException.class).hasMessageContaining("type 'X'");
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
                .isInstanceOf(JkitException.class).hasMessageContaining("folder entry");
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
    void extendedHeadersThatCommonsCompressReadsInItsOwnOrderAreRefused() throws IOException {
        // two PAX headers before one entry: commons-compress applies the first, a scan the last
        byte[] small = pax("size=0");
        byte[] big = pax("size=512");
        Path twoX = tarOf("xx.tar.gz", header("h", 'x', small.length), small, header("h", 'x', big.length), big,
                header("f", '0', 0), new byte[512]);
        assertThatThrownBy(() -> Archives.extract(twoX, tmp.resolve("o1"), "tar.gz"))
                .isInstanceOf(JkitException.class).hasMessageContaining("two 'x' headers");
        // a long name and a PAX path: which wins depends on their order
        byte[] name = "d/\0".getBytes(StandardCharsets.US_ASCII);
        byte[] path = pax("path=f", "size=512");
        Path both = tarOf("lx.tar.gz", header("././@LongLink", 'L', name.length), name, header("h", 'x', path.length),
                path, header("f", '0', 0), new byte[512]);
        assertThatThrownBy(() -> Archives.extract(both, tmp.resolve("o2"), "tar.gz"))
                .hasMessageContaining("both a PAX path and a long name");
        // a global header's name is merged into later entries in an order of its own
        byte[] global = pax("path=d/");
        Path g = tarOf("gp.tar.gz", header("h", 'g', global.length), global, header("f", '0', 0));
        assertThatThrownBy(() -> Archives.extract(g, tmp.resolve("o3"), "tar.gz"))
                .hasMessageContaining("global PAX header sets a name");
        // a long chain of headers before one entry: each one counts as an entry
        byte[][] chain = new byte[24][];
        for (int i = 0; i < 12; i++) {
            chain[2 * i + 1] = pax("comment=c");
            chain[2 * i] = header("h", 'g', chain[2 * i + 1].length);
        }
        Path many = tarOf("gg.tar.gz", chain);
        assertThatThrownBy(() -> Archives.extract(many, tmp.resolve("o4"), "tar.gz"))
                .hasMessageContaining("more than 8 global headers");
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
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip", 10))
                .isInstanceOf(JkitException.class).hasMessageContaining("more than 10 entries");
    }

    @Test
    void zipEntriesMustEachHaveTheirOwnLocalHeader() throws IOException {
        Path archive = tmp.resolve("shared.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            for (String name : new String[] {"jdk/a", "jdk/b"}) {
                zip.putArchiveEntry(new ZipArchiveEntry(name));
                zip.write(new byte[] {1});
                zip.closeArchiveEntry();
            }
        }
        // point the second central entry at the first local header, as a memory bomb would
        byte[] bytes = Files.readAllBytes(archive);
        int seen = 0;
        for (int i = 0; i + 46 < bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2 && ++seen == 2) {
                Arrays.fill(bytes, i + 42, i + 46, (byte) 0);
                break;
            }
        }
        Files.write(archive, bytes);
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip"))
                .isInstanceOf(JkitException.class).hasMessageContaining("share or overlap a local header");
    }

    @Test
    void aNegativeBase256SizeIsRefused() throws IOException {
        // commons-compress reads 0xff... as a negative size; the scan must not read it as a huge one
        byte[] h = header("f", '0', 0);
        Arrays.fill(h, 124, 136, (byte) 0xff);
        Arrays.fill(h, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        System.arraycopy(String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII), 0, h, 148, 8);
        Path archive = tarOf("neg.tar.gz", h);
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "tar.gz"))
                .isInstanceOf(JkitException.class).hasMessageContaining("negative size");
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
    @DisabledOnOs(OS.WINDOWS) // Unix modes
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
                .isInstanceOf(JkitException.class).hasMessageContaining("symlink entry");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks
    void aMacHomeThatIsALinkIsRefused() throws IOException {
        Path out = Files.createDirectories(tmp.resolve("out/jdk-21/Contents"));
        Files.createSymbolicLink(out.resolve("Home"), Path.of(".."));
        assertThatThrownBy(() -> Installer.locateHome(tmp.resolve("out")))
                .isInstanceOf(JkitException.class).hasMessageContaining("symbolic link");
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
        assertThatThrownBy(() -> Archives.extract(archive, tmp.resolve("out"), "zip", 10))
                .isInstanceOf(JkitException.class).hasMessageContaining("more than 10 entries");
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
