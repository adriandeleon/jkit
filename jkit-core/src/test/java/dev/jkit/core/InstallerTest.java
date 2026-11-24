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

    /** Serves {@code file} for the download and {@code signature} (null: 404) for the .sig. */
    private static Http withSignature(Path file, byte[] signature) {
        return new Http() {
            @Override
            public String get(URI uri) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void download(URI uri, Path target, Progress progress) throws IOException {
                if (!uri.getPath().endsWith(".sig") && !uri.getPath().endsWith(".asc")) {
                    Files.copy(file, target);
                } else if (signature == null) {
                    throw new dev.jkit.sources.HttpStatusException(404, uri);
                } else {
                    Files.write(target, signature);
                }
            }
        };
    }

    private static Artifact signed(String checksum) {
        return new Artifact(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"),
                URI.create("https://example.invalid/jdk.tar.gz"), "jdk.tar.gz", "tar.gz", "sha256", checksum, false,
                URI.create("https://example.invalid/jdk.tar.gz.sig"));
    }

    private static byte[] realTemurinSignature() throws IOException {
        try (InputStream in = InstallerTest.class.getResourceAsStream("temurin-21.0.13.1.sig")) {
            return in.readAllBytes();
        }
    }

    @Test
    void aSignatureByTheVendorsKeyThatDoesntMatchRefusesTheInstall() throws Exception {
        // Adoptium's real signature of a real Temurin build, over a different archive
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        assertThatThrownBy(() -> new Installer(home, withSignature(archive, realTemurinSignature()))
                .install(signed(sha256(archive)), Http.Progress.NONE))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("signature of jdk 21.0.13+9 temurin doesn't match the download")
                .hasMessageContaining("Adoptium's key 843C48A565F8F04B made it")
                .hasMessageContaining("nothing was installed");
        assertThat(new Store(home).list(Tool.JDK)).isEmpty();
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    void aSignatureJkitCantCheckInstallsWithAWarning() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, withSignature(archive, "<html>".getBytes()))
                .install(signed(sha256(archive)), Http.Progress.NONE);
        assertThat(result.fresh()).isTrue();
        assertThat(result.verified()).isTrue();
        assertThat(result.signedBy()).isNull();
        assertThat(result.notes()).singleElement().asString().startsWith("warning: couldn't check the signature")
                .contains("only its checksum checked");
        assertThat(Store.signedBy(result.installed().home())).isNull();
    }

    @Test
    void aMissingSignatureInstallsWithAWarning() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, withSignature(archive, null))
                .install(signed(sha256(archive)), Http.Progress.NONE);
        assertThat(result.fresh()).isTrue();
        assertThat(result.notes()).singleElement().asString().startsWith("warning: couldn't fetch the signature");
    }

    /** A Maven-shaped zip: apache-maven-{@code version}/bin/mvn. */
    private static Path mavenZip(Path dir, String version) throws IOException {
        Path zip = dir.resolve("apache-maven-" + version + "-bin.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("apache-maven-" + version + "/bin/mvn"));
            out.write("#!/bin/sh\necho fake maven\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static Artifact tool(Tool tool, String version, Path zip, String checksum) {
        URI url = URI.create("https://example.invalid/" + zip.getFileName());
        return new Artifact(tool, Version.parse(version), null, url, zip.getFileName().toString(), "zip", "sha256",
                checksum, false, URI.create(url + ".asc"));
    }

    @Test
    void mavenIsCheckedAgainstTheReleaseManagersKeys() throws Exception {
        // the real .asc of Maven 3.9.9, by Tamas Cservenak's Ed25519 key, over a zip that isn't 3.9.9
        Path zip = mavenZip(tmp, "3.9.9");
        byte[] asc;
        try (InputStream in = InstallerTest.class.getResourceAsStream("maven-3.9.9.asc")) {
            asc = in.readAllBytes();
        }
        JkitHome home = new JkitHome(tmp.resolve("home"));
        byte[] real = asc;
        assertThatThrownBy(() -> new Installer(home, withSignature(zip, real))
                .install(tool(Tool.MAVEN, "3.9.9", zip, sha256(zip)), Http.Progress.NONE))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("signature of maven 3.9.9 doesn't match the download")
                .hasMessageContaining("Apache Maven's key 3A387D43964143E3 made it");
        assertThat(new Store(home).list(Tool.MAVEN)).isEmpty();
    }

    @Test
    void anSbtSignatureThatCantBeFetchedInstallsWithAWarning() throws Exception {
        Path zip = tmp.resolve("sbt-1.10.7.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("sbt/bin/sbt"));
            out.write("#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, withSignature(zip, null))
                .install(tool(Tool.SBT, "1.10.7", zip, sha256(zip)), Http.Progress.NONE);
        assertThat(result.fresh()).isTrue();
        assertThat(result.signedBy()).isNull();
        assertThat(result.notes()).singleElement().asString()
                .startsWith("warning: couldn't fetch the signature of sbt 1.10.7");
    }

    @Test
    void noSignatureListedMeansNoCheckAndNoNote() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, serving(archive)).install(artifact(sha256(archive)), Http.Progress.NONE);
        assertThat(result.notes()).isEmpty();
        assertThat(result.signedBy()).isNull();
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

        // a copy from another disk that is still going (or was killed) is no second install
        Fs.copyTree(result.installed().home(), home.tools(Tool.JDK).resolve(".21.0.13+9-temurin.partial"));
        assertThat(new Store(home).list(Tool.JDK)).hasSize(1);
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

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aLinkThatClimbsOutOfContentsHomeIsRefused() throws Exception {
        // inside the archive's top folder, so Archives allows it, but outside the part jkit installs
        Path home = Files.createDirectories(tmp.resolve("jdk/Contents/Home"));
        Files.createDirectories(home.resolve("bin"));
        Files.createSymbolicLink(home.resolve("bin/ok"), Path.of("../lib"));
        Installer.requireLinksInside(home);
        Files.createSymbolicLink(home.resolve(Store.MARKER), Path.of("../decoy.properties"));
        assertThatThrownBy(() -> Installer.requireLinksInside(home))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("points outside the folder jkit installs");
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void theInstallRecordIsNeverWrittenOrReadThroughALink() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("tools/jdk/21-temurin"));
        Path decoy = Files.writeString(tmp.resolve("decoy.properties"), "version=99\nverified=true\n");
        Files.createSymbolicLink(dir.resolve(Store.MARKER), decoy);
        assertThat(Store.verified(dir)).isFalse();
        assertThatThrownBy(() -> Store.load(dir.resolve(Store.MARKER))).isInstanceOf(IOException.class);
        Installed installed = new Installed(Tool.JDK, Version.parse("21.0.5"), Vendor.of("temurin"), dir, false);
        Store.writeMarker(dir, installed, "https://example.invalid/jdk.tar.gz", false);
        assertThat(Files.isSymbolicLink(dir.resolve(Store.MARKER))).isFalse();
        assertThat(decoy).hasContent("version=99\nverified=true\n");
        assertThat(Store.verified(dir)).isFalse();
    }

    @Test
    void anOversizedInstallRecordIsIgnored() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("tools/jdk/21-temurin"));
        Files.writeString(dir.resolve(Store.MARKER), "verified=true\n" + "#".repeat((int) Store.MAX_MARKER));
        assertThat(Store.verified(dir)).isFalse();
    }
}
