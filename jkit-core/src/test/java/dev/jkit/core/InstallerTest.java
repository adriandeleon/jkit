package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.Http;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallerTest {
    @TempDir
    Path tmp;

    /** Serves one local file for every download. */
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

    private static String sha256(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }

    private Artifact artifact(String checksum) {
        return artifact(checksum, false);
    }

    private Artifact artifact(String checksum, boolean fx) {
        return new Artifact(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"),
                URI.create("https://example.invalid/jdk.tar.gz"), "jdk.tar.gz", "tar.gz", "sha256", checksum, fx);
    }

    @Test
    void installsIntoToolsAndIsFoundByTheStore() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, serving(archive)).install(artifact(sha256(archive)), Http.Progress.NONE);

        assertThat(result.fresh()).isTrue();
        assertThat(result.verified()).isTrue();
        assertThat(result.installed().home()).isEqualTo(home.tools(Tool.JDK).resolve("21.0.13+9-temurin"));
        assertThat(result.installed().home().resolve("bin/java")).exists();
        assertThat(new Store(home).find(Tool.JDK, "21", Vendor.of("temurin"))).hasSize(1)
                .allSatisfy(i -> assertThat(i.fx()).isFalse());
        assertThat(new Store(home).find(Tool.JDK, "21", Vendor.of("zulu"))).isEmpty();
        assertThat(new Store(home).list(Tool.GRAALVM)).isEmpty();
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    void recordsJavaFxInTheMarker() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, serving(archive))
                .install(artifact(sha256(archive), true), Http.Progress.NONE);

        assertThat(result.installed().fx()).isTrue();
        assertThat(result.installed().label()).isEqualTo("21.0.13+9 temurin fx");
        assertThat(result.installed().home().resolve(Store.MARKER)).content().contains("fx=true");
        assertThat(result.installed().home()).isEqualTo(home.tools(Tool.JDK).resolve("21.0.13+9-temurin-fx"));
        assertThat(new Store(home).list(Tool.JDK)).singleElement().satisfies(i -> assertThat(i.fx()).isTrue());
    }

    @Test
    void javaFxAndPlainBuildsOfOneVersionSitSideBySide() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer installer = new Installer(home, serving(archive));
        Store store = new Store(home);
        installer.install(artifact(sha256(archive), true), Http.Progress.NONE);

        assertThat(store.contains(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"), true)).isTrue();
        assertThat(store.contains(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"), false)).isFalse();

        Installer.Result plain = installer.install(artifact(sha256(archive), false), Http.Progress.NONE);
        assertThat(plain.fresh()).isTrue();
        assertThat(store.list(Tool.JDK)).extracting(Installed::fx).containsExactly(false, true);
    }

    @Test
    void checksumMismatchLeavesNothingBehind() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        String wrong = "0".repeat(64);

        assertThatThrownBy(() -> new Installer(home, serving(archive)).install(artifact(wrong), Http.Progress.NONE))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(home.tools(Tool.JDK).resolve("21.0.13+9-temurin")).doesNotExist();
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    void flattensMacOsContentsHome() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9/Contents/Home");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, serving(archive)).install(artifact(sha256(archive)), Http.Progress.NONE);
        assertThat(result.installed().home().resolve("bin/java")).exists();
        assertThat(result.installed().home().resolve("Contents")).doesNotExist();
    }
}
