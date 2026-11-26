package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Version;
import dev.jkit.sources.Http;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdateTest {
    @TempDir
    Path tmp;

    private static final String SUMS = """
            1111111111111111111111111111111111111111111111111111111111111111  jkit-0.4.0-1.x86_64.rpm
            7777777777777777777777777777777777777777777777777777777777777777  jkit-0.4.0-linux-x64-musl.tar.gz
            2222222222222222222222222222222222222222222222222222222222222222  jkit-0.4.0-linux-x64.tar.gz
            3333333333333333333333333333333333333333333333333333333333333333  jkit-0.4.0-linux-x64.zip
            4444444444444444444444444444444444444444444444444444444444444444  jkit-0.4.0-macos-aarch64.tar.gz
            5555555555555555555555555555555555555555555555555555555555555555  jkit-0.4.0-windows-x64.zip
            6666666666666666666666666666666666666666666666666666666666666666  jkit_0.4.0_amd64.deb
            """;

    @Test
    void parsePicksThisPlatformsArchive() {
        SelfUpdate.Release linux = SelfUpdate.parse(SUMS, Platform.LINUX_X64);
        assertThat(linux.version().raw()).isEqualTo("0.4.0");
        assertThat(linux.archive()).isEqualTo("jkit-0.4.0-linux-x64.tar.gz");
        assertThat(linux.sha256()).startsWith("2222");
        assertThat(SelfUpdate.parse(SUMS, Platform.WINDOWS_X64).archive()).isEqualTo("jkit-0.4.0-windows-x64.zip");
        // the musl build is listed first: glibc Linux must still get its own archive, and musl Linux the musl one
        SelfUpdate.Release musl = SelfUpdate.parse(SUMS, Platform.LINUX_X64_MUSL);
        assertThat(musl.archive()).isEqualTo("jkit-0.4.0-linux-x64-musl.tar.gz");
        assertThat(musl.version().raw()).isEqualTo("0.4.0");
        assertThat(musl.sha256()).startsWith("7777");
        assertThat(linux.url(SelfUpdate.DEFAULT_BASE, linux.archive())).hasToString(
                "https://github.com/adriandeleon/jkit/releases/download/v0.4.0/jkit-0.4.0-linux-x64.tar.gz");
        assertThat(SelfUpdate.sumsUrl(SelfUpdate.DEFAULT_BASE)).hasToString(
                "https://github.com/adriandeleon/jkit/releases/latest/download/SHA256SUMS");
    }

    @Test
    void parseRefusesMissingBuilds() {
        assertThatThrownBy(() -> SelfUpdate.parse(SUMS, Platform.LINUX_AARCH64)).isInstanceOf(JkitException.class)
                .hasMessageContaining("no build for linux-aarch64");
        // a release from before the musl builds: musl Linux doesn't take the glibc archive
        assertThatThrownBy(() -> SelfUpdate.parse(SUMS, Platform.LINUX_AARCH64_MUSL)).isInstanceOf(JkitException.class)
                .hasMessageContaining("no build for linux-aarch64-musl");
        assertThatThrownBy(() -> SelfUpdate.parse("<html>", Platform.LINUX_X64)).isInstanceOf(JkitException.class);
    }

    @Test
    void comparesVersions() {
        SelfUpdate.Release r = SelfUpdate.parse(SUMS, Platform.LINUX_X64);
        assertThat(SelfUpdate.newer(r, "0.3.0")).isTrue();
        assertThat(SelfUpdate.newer(r, "0.4.0-rc.2")).isTrue();
        assertThat(SelfUpdate.newer(r, "0.4.0")).isFalse();
        assertThat(SelfUpdate.newer(r, "0.5.0-SNAPSHOT")).isFalse();
        assertThat(SelfUpdate.newer(r, "unknown version")).isTrue();
    }

    @Test
    void detectsHowJkitWasInstalled() {
        Set<Path> dpkg = Set.of(Path.of("/var/lib/dpkg/info/jkit.list"));
        Set<Path> rpm = Set.of(Path.of("/var/lib/rpm"));
        assertThat(SelfUpdate.detect(Path.of("/opt/homebrew/Cellar/jkit/0.3.0/bin/jkit"), Platform.MACOS_AARCH64,
                p -> false)).isEqualTo(SelfUpdate.Kind.HOMEBREW);
        assertThat(SelfUpdate.detect(Path.of("/home/linuxbrew/.linuxbrew/Cellar/jkit/0.3.0/bin/jkit"),
                Platform.LINUX_X64, p -> false)).isEqualTo(SelfUpdate.Kind.HOMEBREW);
        assertThat(SelfUpdate.detect(Path.of("/usr/bin/jkit"), Platform.LINUX_X64, dpkg::contains))
                .isEqualTo(SelfUpdate.Kind.DEB_FILE); // installed from a file: apt has nowhere to upgrade it from
        Set<Path> vitrina = Set.of(Path.of("/var/lib/dpkg/info/jkit.list"), Path.of("/etc/apt/sources.list.d/vitrina.sources"));
        assertThat(SelfUpdate.detect(Path.of("/usr/bin/jkit"), Platform.LINUX_X64, vitrina::contains))
                .isEqualTo(SelfUpdate.Kind.DEB);
        assertThat(SelfUpdate.Kind.DEB_FILE.id()).isEqualTo("deb");
        assertThat(SelfUpdate.detect(Path.of("/usr/bin/jkit"), Platform.LINUX_X64, rpm::contains))
                .isEqualTo(SelfUpdate.Kind.RPM);
        assertThat(SelfUpdate.detect(Path.of("C:/Users/me/AppData/Local/Microsoft/WinGet/Packages/AdrianDeLeon.jkit_x/jkit.exe"),
                Platform.WINDOWS_X64, p -> false)).isEqualTo(SelfUpdate.Kind.WINGET);
        assertThat(SelfUpdate.detect(Path.of("/home/me/bin/jkit"), Platform.LINUX_X64, dpkg::contains))
                .isEqualTo(SelfUpdate.Kind.ARCHIVE);
    }

    @Test
    void packageManagersGetTheirCommand() {
        SelfUpdate.Release r = SelfUpdate.parse(SUMS, Platform.LINUX_X64);
        assertThat(SelfUpdate.Kind.HOMEBREW.command(r, Platform.LINUX_X64)).isEqualTo("brew upgrade jkit");
        assertThat(SelfUpdate.Kind.RPM.command(r, Platform.LINUX_X64)).isEqualTo(
                "sudo dnf install https://github.com/adriandeleon/jkit/releases/download/v0.4.0/jkit-0.4.0-1.x86_64.rpm");
        assertThat(SelfUpdate.Kind.DEB_FILE.command(r, Platform.LINUX_AARCH64)).isEqualTo(
                "d=$(mktemp -d) && curl -fLo \"$d/jkit_0.4.0_arm64.deb\" https://github.com/adriandeleon/jkit/releases/"
                        + "download/v0.4.0/jkit_0.4.0_arm64.deb && sudo apt install \"$d/jkit_0.4.0_arm64.deb\"");
        assertThat(SelfUpdate.Kind.ARCHIVE.command(r, Platform.LINUX_X64)).isNull();
        // a pre-release's package names, as GitHub publishes them
        SelfUpdate.Release rc = new SelfUpdate.Release(Version.parse("0.4.0-rc.1"), "x", "0".repeat(64));
        assertThat(SelfUpdate.Kind.RPM.command(rc, Platform.LINUX_X64)).endsWith("/v0.4.0-rc.1/jkit-0.4.0.rc.1-1.x86_64.rpm");
    }

    /** A release archive whose jkit is a script printing {@code says}. */
    private static byte[] archive(String says) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(raw))) {
            byte[] body = ("#!/bin/sh\necho '" + says + "'\n").getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry("jkit");
            e.setMode(0755);
            e.setSize(body.length);
            tar.putArchiveEntry(e);
            tar.write(body);
            tar.closeArchiveEntry();
        }
        return raw.toByteArray();
    }

    private static Http serving(byte[] bytes) {
        return new Http() {
            @Override
            public String get(URI uri) {
                throw new AssertionError("no lists here");
            }

            @Override
            public void download(URI uri, Path target, Progress progress) throws IOException {
                assertThat(uri).hasToString("https://example.test/download/v0.4.0/jkit-0.4.0-linux-x64.tar.gz");
                Files.write(target, bytes);
            }
        };
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void fetchChecksAndReplaceSwapsTheBinary() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        byte[] bytes = archive("jkit 0.4.0");
        SelfUpdate.Release r = new SelfUpdate.Release(Version.parse("0.4.0"),
                "jkit-0.4.0-linux-x64.tar.gz", sha256(bytes));
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path fresh = SelfUpdate.fetch(serving(bytes), URI.create("https://example.test/"), r, Platform.LINUX_X64, work,
                Http.Progress.NONE);

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path binary = Files.writeString(bin.resolve("jkit"), "old");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(SelfUpdate.replace(fresh, binary, false)).isNull();
        assertThat(Files.readString(binary)).contains("jkit 0.4.0");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(binary))).isEqualTo("rwxr-x---");
        try (var left = Files.list(bin)) {
            assertThat(left).containsExactly(binary); // no staged copy left behind
        }
    }

    @Test
    void aBadChecksumOrABinaryThatDoesntRunChangesNothing() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        byte[] bytes = archive("jkit 0.4.0");
        Path work = Files.createDirectories(tmp.resolve("work"));
        SelfUpdate.Release wrongSum = new SelfUpdate.Release(Version.parse("0.4.0"),
                "jkit-0.4.0-linux-x64.tar.gz", "0".repeat(64));
        assertThatThrownBy(() -> SelfUpdate.fetch(serving(bytes), URI.create("https://example.test/"), wrongSum,
                Platform.LINUX_X64, work, Http.Progress.NONE)).hasMessageContaining("checksum mismatch");

        byte[] other = archive("jkit 0.3.9");
        SelfUpdate.Release lies = new SelfUpdate.Release(Version.parse("0.4.0"),
                "jkit-0.4.0-linux-x64.tar.gz", sha256(other));
        Path work2 = Files.createDirectories(tmp.resolve("work2"));
        assertThatThrownBy(() -> SelfUpdate.fetch(serving(other), URI.create("https://example.test/"), lies,
                Platform.LINUX_X64, work2, Http.Progress.NONE)).hasMessageContaining("doesn't run here");
    }

    @Test
    void windowsKeepsTheOldExeAside() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path binary = Files.writeString(bin.resolve("jkit.exe"), "old");
        Path fresh = Files.writeString(tmp.resolve("new.exe"), "new");
        Path old = SelfUpdate.replace(fresh, binary, true);
        assertThat(Files.readString(binary)).isEqualTo("new");
        assertThat(Files.readString(old)).isEqualTo("old");
        assertThat(SelfUpdate.deleteOld(binary)).containsExactly(old);
        assertThat(old).doesNotExist();
        assertThat(SelfUpdate.deleteOld(binary)).isEmpty();
    }

    @Test
    void windowsReplacesOnlyTheRuntimeDllsAlreadyBesideJkit() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path binary = Files.writeString(bin.resolve("jkit.exe"), "old");
        Files.writeString(bin.resolve("vcruntime140.dll"), "old runtime");
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path fresh = Files.writeString(out.resolve("jkit.exe"), "new");
        Files.writeString(out.resolve("vcruntime140.dll"), "new runtime");
        Files.writeString(out.resolve("vcruntime140_1.dll"), "new runtime 1");

        // vcruntime140_1.dll isn't beside jkit.exe: never added to a folder that didn't have it
        assertThat(SelfUpdate.runtimeToReplace(fresh, binary)).containsExactly("vcruntime140.dll");
        Path dll = bin.resolve("vcruntime140.dll");
        Path oldDll = SelfUpdate.replace(out.resolve("vcruntime140.dll"), dll, true);
        Path oldExe = SelfUpdate.replace(fresh, binary, true);
        assertThat(Files.readString(dll)).isEqualTo("new runtime");
        assertThat(bin.resolve("vcruntime140_1.dll")).doesNotExist();
        assertThat(SelfUpdate.deleteOld(binary)).containsExactlyInAnyOrder(oldExe, oldDll);
        try (var left = Files.list(bin)) {
            assertThat(left.map(f -> f.getFileName().toString())).containsExactlyInAnyOrder("jkit.exe", "vcruntime140.dll");
        }

        // an archive without the DLLs (a release before they were bundled) replaces only jkit.exe
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        assertThat(SelfUpdate.runtimeToReplace(Files.writeString(bare.resolve("jkit.exe"), "x"), binary)).isEmpty();
    }

    @Test
    void anUnwritableFolderIsRefusedUpFront() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        assumeFalse("root".equals(System.getProperty("user.name")));
        Path bin = Files.createDirectories(tmp.resolve("ro"));
        Path binary = Files.writeString(bin.resolve("jkit"), "old");
        Path fresh = Files.writeString(tmp.resolve("new"), "new");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(() -> SelfUpdate.replace(fresh, binary, false)).hasMessageContaining("can't write to");
            assertThat(Files.readString(binary)).isEqualTo("old");
        } finally {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

}
