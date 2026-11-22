package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.Http;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Core fixes from the review: bad markers and config, odd homes, encodings, installer safety. */
class RobustnessTest {
    @TempDir
    Path tmp;

    private static Http serving(Path file) {
        return new Http() {
            @Override
            public String get(URI uri) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void download(URI uri, Path target, Progress progress) throws IOException {
                Files.copy(file, target);
            }
        };
    }

    private static Artifact artifact(String url, String fileName, String algorithm, String checksum) {
        return new Artifact(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"), URI.create(url), fileName,
                "tar.gz", algorithm, checksum, false);
    }

    private static String digest(Path file, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(file)));
    }

    @Test
    void damagedMarkerIsSkippedNotACrash() throws IOException {
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Path broken = Files.createDirectories(home.tools(Tool.JDK).resolve("21-temurin"));
        Files.writeString(broken.resolve(Store.MARKER), "tool=jdk\nvendor=temurin\n"); // no version
        assertThat(new Store(home).list(Tool.JDK)).isEmpty();
    }

    @Test
    void wrongTypeInConfigIsAClearError() throws IOException {
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Files.createDirectories(home.root());
        Files.writeString(home.config(), "[jdk]\nvendor = 1\n");
        assertThatThrownBy(() -> Config.load(home, Map.of()))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("jdk.vendor must be a string");
    }

    @Test
    void autoSwitchIsOffByDefaultAndReadFromConfigOrEnvironment() throws IOException {
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Files.createDirectories(home.root());
        assertThat(Config.load(home, Map.of()).autoSwitch()).isFalse();
        Files.writeString(home.config(), "[shell]\nauto_switch = true\n");
        assertThat(Config.load(home, Map.of()).autoSwitch()).isTrue();
        assertThat(Config.load(home, Map.of("JKIT_AUTO_SWITCH", "0")).autoSwitch()).isFalse();
        Files.writeString(home.config(), "[shell]\nauto_switch = \"yes\"\n");
        assertThatThrownBy(() -> Config.load(home, Map.of())).hasMessageContaining("shell.auto_switch must be true or false");
        Files.delete(home.config());
        assertThat(Config.load(home, Map.of("JKIT_AUTO_SWITCH", "1")).autoSwitch()).isTrue();
        assertThatThrownBy(() -> Config.load(home, Map.of("JKIT_AUTO_SWITCH", "maybe"))).hasMessageContaining("isn't 1 or 0");
    }

    @Test
    void homeIsNormalized() {
        JkitHome home = JkitHome.fromEnv(Map.of("JKIT_HOME", tmp.resolve("a/../jkit").toString()));
        assertThat(home.root()).isEqualTo(tmp.resolve("jkit"));
    }

    @Test
    void projectFilesWithBomOrUtf16AreRead() throws IOException {
        Path bom = Files.createDirectories(tmp.resolve("bom"));
        Files.write(bom.resolve(".java-version"), new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '2', '1', '\n'});
        Optional<ProjectSpec> spec = ProjectFile.find(bom, Map.of());
        assertThat(spec).get().extracting(s -> s.get(Tool.JDK).version()).isEqualTo("21");

        Path utf16 = Files.createDirectories(tmp.resolve("utf16"));
        byte[] text = "17\r\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[text.length + 2];
        withBom[0] = (byte) 0xff;
        withBom[1] = (byte) 0xfe;
        System.arraycopy(text, 0, withBom, 2, text.length);
        Files.write(utf16.resolve(".java-version"), withBom);
        assertThat(ProjectFile.find(utf16, Map.of())).get().extracting(s -> s.get(Tool.JDK).version()).isEqualTo("17");
    }

    @Test
    void remoteFileNamesCannotLeaveTheWorkFolder() {
        assertThat(Installer.safeFileName("../../evil.tar.gz", "tar.gz")).isEqualTo("evil.tar.gz");
        assertThat(Installer.safeFileName("..", "zip")).isEqualTo("download.zip");
        assertThat(Installer.safeFileName("a\\b\\c.zip", "zip")).isEqualTo("c.zip");
    }

    @Test
    void plainHttpDownloadIsRefused() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        assertThatThrownBy(() -> new Installer(home, serving(archive))
                .install(artifact("http://example.com/jdk.tar.gz", "jdk.tar.gz", null, null), Http.Progress.NONE))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("refusing to download over http");
    }

    @Test
    void weakChecksumIsCheckedButNotVerified() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, serving(archive)).install(
                artifact("https://example.invalid/jdk.tar.gz", "jdk.tar.gz", "sha1", digest(archive, "SHA-1")),
                Http.Progress.NONE);
        assertThat(result.verified()).isFalse();
        // and an existing install reports what was recorded, not "verified"
        assertThat(new Installer(home, serving(archive)).install(
                artifact("https://example.invalid/jdk.tar.gz", "jdk.tar.gz", "sha1", "x"), Http.Progress.NONE).verified())
                .isFalse();
    }

    @Test
    void leftoversFromAnInterruptedInstallAreCleanedUp() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Path leftover = Files.createDirectories(home.tmp().resolve("install-123/out"));
        Installer.Result result = new Installer(home, serving(archive)).install(
                artifact("https://example.invalid/jdk.tar.gz", "jdk.tar.gz", "sha256", digest(archive, "SHA-256")),
                Http.Progress.NONE);
        assertThat(leftover.getParent()).doesNotExist();
        assertThat(result.removedLeftovers()).containsExactly(leftover.getParent()); // so the command can say so
    }

    @Test
    void folderWithoutMarkerIsNotTakenForAnInstall() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Files.createDirectories(home.tools(Tool.JDK).resolve("21.0.13+9-temurin"));
        assertThat(new Store(home).contains(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"), false)).isFalse();
        assertThatThrownBy(() -> new Installer(home, serving(archive)).install(
                artifact("https://example.invalid/jdk.tar.gz", "jdk.tar.gz", null, null), Http.Progress.NONE))
                .hasMessageContaining("isn't a jkit install");
    }

    @Test
    void pathFilterComparesWholeFolders() {
        JkitHome home = new JkitHome(tmp.resolve("home"));
        String keep = tmp.resolve("home/tools-old/bin").toString();
        String drop = tmp.resolve("home/tools/jdk/21/bin").toString();
        Map<String, String> env = Map.of("PATH", String.join(File.pathSeparator, drop, keep));
        String path = ShellEnv.path(env, home, new java.util.EnumMap<>(Tool.class));
        assertThat(path).isEqualTo(keep);
    }

    @Test
    void shellEnvQuotesForPowerShellAndFish() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("JAVA_HOME", "C:\\Users\\O'Brien\\jdk");
        assertThat(ShellEnv.format(changes, "pwsh")).isEqualTo("$env:JAVA_HOME = 'C:\\Users\\O''Brien\\jdk'\n");
        assertThat(ShellEnv.format(changes, "fish")).isEqualTo("set -gx JAVA_HOME 'C:\\\\Users\\\\O\\'Brien\\\\jdk'\n");
    }

    @Test
    void graalVmHomeFollowingAGlobalGraalVmJdkIsNotStale() throws Exception {
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Path graal = Files.createDirectories(home.tools(Tool.JDK).resolve("25.0.1-graalvm_community"));
        Files.writeString(graal.resolve(Store.MARKER), "tool=jdk\nversion=25.0.1\nvendor=graalvm_community\nfx=false\n");
        Files.createDirectories(home.root().resolve("global"));
        Files.createSymbolicLink(home.global(Tool.JDK), graal);
        Map<String, String> env = Map.of(
                "JAVA_HOME", home.global(Tool.JDK).toString(),
                "GRAALVM_HOME", home.global(Tool.JDK).toString(), // what jkit env sets
                ProjectFile.OVERRIDE, "");
        ToolStates.Result r = ToolStates.read(env, tmp, home, new Store(home), Config.load(home, Map.of()),
                link -> Files.isSymbolicLink(link) ? link.getParent().resolve(Files.readSymbolicLink(link)).normalize() : null);
        assertThat(r.get(Tool.GRAALVM).source()).isEqualTo(ToolState.Source.GLOBAL);
        assertThat(r.get(Tool.GRAALVM).matches()).isTrue();
    }
}
