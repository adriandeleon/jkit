package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.Http;
import dev.jkit.sources.HttpStatusException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
                    throw new HttpStatusException(404, uri);
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
    void aSignatureJkitCantCheckRefusesTheInstallEvenWithAChecksum() throws Exception {
        // whoever serves the download and its checksum could serve no signature, or their own
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        for (byte[] sig : new byte[][] {"<html>".getBytes(), null}) {
            assertThatThrownBy(() -> new Installer(home, withSignature(archive, sig))
                    .install(signed(sha256(archive)), Http.Progress.NONE, true))
                    .isInstanceOf(Installer.SignatureRequired.class)
                    .hasMessageContaining("signature of jdk 21.0.13+9 temurin")
                    .hasMessageEndingWith("; nothing was installed")
                    .hasMessageNotContaining("no published checksum");
        }
        assertThat(new Store(home).list(Tool.JDK)).isEmpty();
        assertThat(home.tmp()).isEmptyDirectory();
    }

    /** Progress that counts its starting() calls in {@code started}. */
    private static Http.Progress countingStarts(AtomicInteger started) {
        return new Http.Progress() {
            @Override
            public void update(long done, long total) { }

            @Override
            public void starting() {
                started.incrementAndGet();
            }
        };
    }

    @Test
    void allowedUnverifiedASignatureJkitCantCheckInstallsUnverifiedWithAWarning() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        AtomicInteger started = new AtomicInteger();
        Http.Progress progress = countingStarts(started);
        Installer.Result result = new Installer(home, withSignature(archive, "<html>".getBytes()))
                .install(signed(sha256(archive)), progress);
        assertThat(started).hasValue(1);
        assertThat(result.fresh()).isTrue();
        assertThat(result.verified()).isFalse();
        assertThat(Store.verified(result.installed().home())).isFalse();
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
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("apache-maven-" + version + "/bin/mvn"));
            out.write("#!/bin/sh\necho fake maven\n".getBytes(StandardCharsets.UTF_8));
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
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("sbt/bin/sbt"));
            out.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
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

    /** A Corretto build as foojay lists it: no checksum, a .sig jkit derives. */
    private static Artifact corretto() {
        URI url = URI.create("https://corretto.aws/downloads/resources/21.0.13.1.2/jdk.tar.gz");
        return new Artifact(Tool.JDK, Version.parse("21.0.13.1+2"), Vendor.of("corretto"), url, "jdk.tar.gz", "tar.gz",
                null, null, false, URI.create(url + ".sig"));
    }

    @Test
    void withNoChecksumASignatureThatCantBeCheckedRefusesTheInstall() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        // junk, a 404, and a signature by another vendor's key: none may stand in for the checksum
        for (byte[] sig : new byte[][] {"<html>".getBytes(), null, realTemurinSignature()}) {
            assertThatThrownBy(() -> new Installer(home, withSignature(archive, sig))
                    .install(corretto(), Http.Progress.NONE, true))
                    .isInstanceOf(Installer.SignatureRequired.class)
                    .hasMessageContaining("signature of jdk 21.0.13.1+2 corretto")
                    .hasMessageEndingWith("and it has no published checksum; nothing was installed");
        }
        // and one that doesn't match is refused as always
        byte[] amazon;
        try (InputStream in = InstallerTest.class.getResourceAsStream("corretto-21.0.13.1.2.sig")) {
            amazon = in.readAllBytes();
        }
        assertThatThrownBy(() -> new Installer(home, withSignature(archive, amazon))
                .install(corretto(), Http.Progress.NONE, true))
                .hasMessageContaining("Amazon's key A122542AB04F24E3 made it");
        assertThat(new Store(home).list(Tool.JDK)).isEmpty();
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    void aCorrettoBuildSignedWithARetiredKeyIsRefusedWithoutSendingYouToSelfUpdate() throws Exception {
        // Amazon's real signature of its 11.0.4.11.1 Linux build, made with that release's own key
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        byte[] retired;
        try (InputStream in = InstallerTest.class.getResourceAsStream("corretto-11.0.4.11.1.sig")) {
            retired = in.readAllBytes();
        }
        assertThatThrownBy(() -> new Installer(home, withSignature(archive, retired))
                .install(corretto(), Http.Progress.NONE, true))
                .isInstanceOf(Installer.SignatureRequired.class)
                .hasMessageContaining("signed with Amazon's retired key 5A812B5B67F4FCB4")
                .hasMessageContaining("(pick a newer build)")
                .hasMessageNotContaining("self-update")
                .hasMessageEndingWith("and it has no published checksum; nothing was installed");
        // a key jkit doesn't know at all may be a new one
        assertThatThrownBy(() -> new Installer(home, withSignature(archive, realTemurinSignature()))
                .install(corretto(), Http.Progress.NONE, true))
                .hasMessageContaining("a newer jkit may know a new key: jkit self-update");
        assertThat(new Store(home).list(Tool.JDK)).isEmpty();
    }

    @Test
    void aSignatureThatRefusesTheInstallDoesSoBeforeTheDownload() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        byte[] retired;
        try (InputStream in = InstallerTest.class.getResourceAsStream("corretto-11.0.4.11.1.sig")) {
            retired = in.readAllBytes();
        }
        // none published, junk, a retired key and another vendor's key: all known from the .sig alone
        for (byte[] sig : new byte[][] {null, "<html>".getBytes(), retired, realTemurinSignature()}) {
            java.util.List<URI> fetched = new java.util.ArrayList<>();
            Http http = withSignature(archive, sig);
            Http counting = new Http() {
                @Override
                public String get(URI uri) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void download(URI uri, Path target, Progress progress) throws IOException {
                    fetched.add(uri);
                    http.download(uri, target, progress);
                }
            };
            AtomicInteger started = new AtomicInteger();
            Http.Progress progress = countingStarts(started);
            assertThatThrownBy(() -> new Installer(home, counting).install(corretto(), progress, true))
                    .isInstanceOf(Installer.SignatureRequired.class);
            assertThat(fetched).as("only the signature").containsExactly(corretto().signature());
            assertThat(started).as("no \"downloading\" before a refusal").hasValue(0);
        }
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    void aSignedJdkMustBeTheVersionAskedFor() throws Exception {
        Path jdk = Files.createDirectories(tmp.resolve("jdk"));
        // what amazon-corretto-21.0.13.1.2-linux-x64.tar.gz says
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.13.1\"\nIMPLEMENTOR=\"Amazon.com Inc.\"\n");
        Installer.requireReleaseVersion(jdk, corretto());

        // every part counts: 21.0.13 is signed too, but lacks the fixes of 21.0.13.1
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.13\"\n");
        assertThatThrownBy(() -> Installer.requireReleaseVersion(jdk, corretto()))
                .hasMessageContaining("is signed, but it is JDK 21.0.13; nothing was installed");
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.13.1\"\n");
        assertThatThrownBy(() -> Installer.requireReleaseVersion(jdk, signed("x")))
                .hasMessageContaining("jdk 21.0.13+9 temurin is signed, but it is JDK 21.0.13.1");
        // a .0 is no difference, nor is the build number, which the release file doesn't carry
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.13.0\"\n");
        Installer.requireReleaseVersion(jdk, signed("x"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"17\"\n");
        Installer.requireReleaseVersion(jdk, new Artifact(Tool.JDK, Version.parse("17+35"), Vendor.of("temurin"),
                URI.create("https://example.invalid/jdk.tar.gz"), "jdk.tar.gz", "tar.gz", "sha256", "x", false,
                URI.create("https://example.invalid/jdk.tar.gz.sig")));

        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.5\"\n");
        assertThatThrownBy(() -> Installer.requireReleaseVersion(jdk, corretto()))
                .hasMessageContaining("is signed, but it is JDK 21.0.5; nothing was installed");
        Files.delete(jdk.resolve("release"));
        assertThatThrownBy(() -> Installer.requireReleaseVersion(jdk, corretto()))
                .hasMessageContaining("names its version in neither a release file nor version.txt");

        // Java 8 writes its old numbering
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"1.8.0_514\"\n");
        URI url = URI.create("https://corretto.aws/downloads/resources/8.514.01.1/jdk.tar.gz");
        Artifact eight = new Artifact(Tool.JDK, Version.parse("8.0.514"), Vendor.of("corretto"), url, "jdk.tar.gz",
                "tar.gz", null, null, false, URI.create(url + ".sig"));
        Installer.requireReleaseVersion(jdk, eight);
    }

    @Test
    void correttoEightHasNoReleaseFileSoItsVersionTxtIsRead() throws Exception {
        // the layout of amazon-corretto-8.514.01.1-linux-x64.tar.gz: version.txt, no release file
        Path jdk = Files.createDirectories(tmp.resolve("corretto8"));
        Files.writeString(jdk.resolve("version.txt"), "8.514.01.1\n");
        URI url = URI.create("https://corretto.aws/downloads/resources/8.514.01.1/jdk.tar.gz");
        Artifact eight = new Artifact(Tool.JDK, Version.parse("8.0.514"), Vendor.of("corretto"), url, "jdk.tar.gz",
                "tar.gz", null, null, false, URI.create(url + ".sig"));
        Installer.requireReleaseVersion(jdk, eight);

        // an older signed build is still refused
        Files.writeString(jdk.resolve("version.txt"), "8.482.08.1\n");
        assertThatThrownBy(() -> Installer.requireReleaseVersion(jdk, eight))
                .hasMessageContaining("is signed, but it is JDK 8.482.08.1; nothing was installed");

        // later Corretto releases write the usual numbering there
        Files.writeString(jdk.resolve("version.txt"), "21.0.13.1.2\n");
        Installer.requireReleaseVersion(jdk, corretto());

        // the release file wins when both are there
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.5\"\n");
        assertThatThrownBy(() -> Installer.requireReleaseVersion(jdk, corretto()))
                .hasMessageContaining("is signed, but it is JDK 21.0.5");
    }

    @Test
    void correttoEightNumberingMapsToTheUsualOne() {
        assertThat(Installer.fromCorrettoEight("8.514.01.1")).isEqualTo("8.0.514.01.1");
        assertThat(Installer.fromCorrettoEight("8.0.514")).isEqualTo("8.0.514");
        assertThat(Installer.fromCorrettoEight("11.0.33.1.1")).isEqualTo("11.0.33.1.1");
    }

    @Test
    void aRequiredSignatureNeedsOneJkitCanCheck() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Artifact unsigned = new Artifact(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("zulu"),
                URI.create("https://example.invalid/jdk.tar.gz"), "jdk.tar.gz", "tar.gz", null, null, false);
        assertThatThrownBy(() -> new Installer(home, serving(archive)).install(unsigned, Http.Progress.NONE, true))
                .isInstanceOf(Installer.SignatureRequired.class)
                .hasMessageContaining("no published checksum or signature jkit can check");
    }

    @Test
    void allowedUnverifiedTheSignatureIsStillCheckedAndTheWarningSaysUnchecked() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, withSignature(archive, null))
                .install(corretto(), Http.Progress.NONE, false);
        assertThat(result.verified()).isFalse();
        assertThat(result.notes()).singleElement().asString()
                .startsWith("warning: couldn't fetch the signature of jdk 21.0.13.1+2 corretto")
                .endsWith("installed unchecked (--allow-unverified)");
    }

    @Test
    void aBuildOfASigningVendorWithNoSignatureSaysOnlyItsChecksumWasChecked() throws Exception {
        // a Temurin build from before Adoptium signed them: the checksum is the check, and the user is told
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Installer.Result result = new Installer(home, serving(archive)).install(artifact(sha256(archive)), Http.Progress.NONE);
        assertThat(result.verified()).isTrue();
        assertThat(result.signedBy()).isNull();
        assertThat(result.notes()).containsExactly("note: no signature jkit can check is published for"
                + " jdk 21.0.13+9 temurin; only its checksum was checked");
    }

    @Test
    void aVendorJkitHasNoKeysForGetsNoNote() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Artifact zulu = new Artifact(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("zulu"),
                URI.create("https://example.invalid/jdk.tar.gz"), "jdk.tar.gz", "tar.gz", "sha256", sha256(archive), false);
        Installer.Result result = new Installer(home, serving(archive)).install(zulu, Http.Progress.NONE);
        assertThat(result.verified()).isTrue();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void aSignedMavenMustBeTheVersionAskedFor() throws Exception {
        Path maven = Files.createDirectories(tmp.resolve("apache-maven-3.9.11/lib"));
        Files.writeString(maven.resolve("maven-core-3.9.11.jar"), "jar");
        Path zip = tmp.resolve("apache-maven-3.9.11-bin.zip");
        Installer.requireMavenVersion(maven.getParent(), tool(Tool.MAVEN, "3.9.11", zip, "x"));
        assertThatThrownBy(() -> Installer.requireMavenVersion(maven.getParent(), tool(Tool.MAVEN, "3.9.12", zip, "x")))
                .hasMessageContaining("maven 3.9.12 is signed, but it is Maven 3.9.11; nothing was installed");
        // Maven 4 names its core the same way
        Files.move(maven.resolve("maven-core-3.9.11.jar"), maven.resolve("maven-core-4.0.0-rc-4.jar"));
        Installer.requireMavenVersion(maven.getParent(), tool(Tool.MAVEN, "4.0.0-rc-4", zip, "x"));
        Files.delete(maven.resolve("maven-core-4.0.0-rc-4.jar"));
        assertThatThrownBy(() -> Installer.requireMavenVersion(maven.getParent(), tool(Tool.MAVEN, "4.0.0-rc-4", zip, "x")))
                .hasMessageContaining("a build that doesn't name its version in lib/maven-core-*.jar");
    }

    /** An sbt install whose launcher names {@code version}, as sbt-launch.jar's sbt.boot.properties does. */
    private Path sbtHome(String version) throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("sbt-" + version + "/bin"));
        try (ZipOutputStream out = new ZipOutputStream(
                Files.newOutputStream(bin.resolve("sbt-launch.jar")))) {
            out.putNextEntry(new ZipEntry("sbt/sbt.boot.properties"));
            out.write(("[scala]\n  version: ${sbt.scala.version-auto}\n\n[app]\n  org: ${sbt.organization-org.scala-sbt}\n"
                    + "  name: sbt\n  version: ${sbt.version-read(sbt.version)[" + version + "]}\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bin.getParent();
    }

    @Test
    void aSignedSbtMustBeTheVersionAskedFor() throws Exception {
        Path zip = tmp.resolve("sbt.zip");
        Installer.requireSbtVersion(sbtHome("1.10.7"), tool(Tool.SBT, "1.10.7", zip, "x"));
        Installer.requireSbtVersion(sbtHome("2.0.0-RC2"), tool(Tool.SBT, "2.0.0-RC2", zip, "x"));
        assertThatThrownBy(() -> Installer.requireSbtVersion(sbtHome("1.10.6"), tool(Tool.SBT, "1.10.7", zip, "x")))
                .hasMessageContaining("sbt 1.10.7 is signed, but it is sbt 1.10.6; nothing was installed");
        Path none = Files.createDirectories(tmp.resolve("none/bin"));
        assertThatThrownBy(() -> Installer.requireSbtVersion(none.getParent(), tool(Tool.SBT, "1.10.7", zip, "x")))
                .hasMessageContaining("doesn't name its version in bin/sbt-launch.jar");
        Files.writeString(none.resolve("sbt-launch.jar"), "not a jar");
        assertThatThrownBy(() -> Installer.requireSbtVersion(none.getParent(), tool(Tool.SBT, "1.10.7", zip, "x")))
                .hasMessageContaining("doesn't name its version in bin/sbt-launch.jar");
    }

    @Test
    void anOversizedSignatureIsNotReadWhole() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        byte[] huge = new byte[Signatures.MAX_SIGNATURE + 1];
        assertThatThrownBy(() -> new Installer(home, withSignature(archive, huge))
                .install(signed(sha256(archive)), Http.Progress.NONE, true))
                .isInstanceOf(Installer.SignatureRequired.class)
                .hasMessageContaining("couldn't fetch the signature of jdk 21.0.13+9 temurin")
                .hasMessageContaining("sent more than 64 KB");
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
    @DisabledOnOs(OS.WINDOWS)
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
    @DisabledOnOs(OS.WINDOWS)
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

    /** Serves {@code file} for every download, after running {@code during} (what another jkit does meanwhile). */
    private static Http servingWhile(Path file, IoAction during) {
        return new Http() {
            @Override
            public String get(URI uri) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void download(URI uri, Path target, Progress progress) throws IOException {
                during.run();
                Files.copy(file, target);
            }
        };
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    @Test
    void aCopyToAnotherDiskIsDeletedByCtrlCOrWhenItFails() throws Exception {
        Path from = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9").resolveSibling("unpacked");
        Files.createDirectories(from.resolve("bin"));
        Path to = Files.createDirectories(tmp.resolve("tools")).resolve("21.0.13+9-temurin");
        Path partial = to.resolveSibling(".21.0.13+9-temurin.partial");
        // Ctrl-C halfway through the copy: jkit's signal handler deletes what is in WorkDirs
        assertThatThrownBy(() -> Installer.copyAcross(from, to, (a, b) -> {
            Fs.copyTree(a, b);
            assertThat(WorkDirs.deleteAll()).contains(b);
            throw new IOException("interrupted");
        })).hasMessage("interrupted");
        assertThat(partial).doesNotExist();
        // a copy that fails is deleted too, and isn't left for the signal handler
        assertThatThrownBy(() -> Installer.copyAcross(from, to, (a, b) -> {
            Fs.copyTree(a, b);
            throw new IOException("disk full");
        })).hasMessage("disk full");
        assertThat(partial).doesNotExist();
        assertThat(WorkDirs.deleteAll()).doesNotContain(partial);
        // one that works ends as the install, in one rename
        Installer.copyAcross(from, to, Fs::copyTree);
        assertThat(to.resolve("bin")).isDirectory();
        assertThat(partial).doesNotExist();
        assertThat(from).doesNotExist();
    }

    @Test
    void theInstallLockIsNotHeldDuringTheDownload() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Store store = new Store(home);
        // remove, prune or another install takes the lock while this one downloads: in one JVM, a
        // lock still held would throw OverlappingFileLockException here rather than wait
        Installer.Result result = new Installer(home, servingWhile(archive, () -> {
            try (AutoCloseable lock = store.lock()) {
                assertThat(lock).isNotNull();
            } catch (Exception e) {
                throw new IOException(e);
            }
        })).install(artifact(sha256(archive)), Http.Progress.NONE);
        assertThat(result.fresh()).isTrue();
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    void anInstallThatAnotherJkitFinishedFirstKeepsTheirsAndSaysItIsInstalled() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Path dest = home.tools(Tool.JDK).resolve("21.0.13+9-temurin");
        Installed theirs = new Installed(Tool.JDK, Version.parse("21.0.13+9"), Vendor.of("temurin"), dest, false);
        Installer.Result result = new Installer(home, servingWhile(archive, () -> {
            Files.createDirectories(dest.resolve("bin"));
            Store.writeMarker(dest, theirs, "https://example.invalid/theirs.tar.gz", true);
        })).install(artifact(sha256(archive)), Http.Progress.NONE);
        assertThat(result.fresh()).isFalse();
        assertThat(result.verified()).isTrue();
        assertThat(dest.resolve(Store.MARKER)).content().contains("theirs.tar.gz");
        assertThat(dest.resolve("bin/java")).doesNotExist(); // ours was discarded, not moved over theirs
        assertThat(home.tmp()).isEmptyDirectory();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // a folder with a file open in it can't be deleted there anyway
    void anotherJkitsDownloadFolderIsNoLeftover() throws Exception {
        Path archive = ArchivesTest.jdkTarGz(tmp, "jdk-21.0.13+9");
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Path leftover = Files.createDirectories(home.tmp().resolve("install-1/out"));
        // another jkit, downloading: a JVM of its own holds its work folder
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process other = new ProcessBuilder(jvm, "-cp", System.getProperty("java.class.path"),
                WorkDirsTest.Holder.class.getName(), home.tmp().toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            Path theirs = Path.of(new BufferedReader(new InputStreamReader(other.getInputStream(),
                    StandardCharsets.UTF_8)).readLine());
            assertThat(WorkDirs.inUse(theirs)).isTrue();
            Installer.Result result = new Installer(home, serving(archive)).install(artifact(sha256(archive)),
                    Http.Progress.NONE);
            assertThat(result.removedLeftovers()).containsExactly(leftover.getParent());
            assertThat(theirs).isDirectory();
            // it ends without cleaning up, as when killed: its folder is a leftover now
            other.getOutputStream().close();
            assertThat(other.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(theirs).isDirectory();
            assertThat(WorkDirs.inUse(theirs)).isFalse();
            Fs.deleteRecursively(result.installed().home());
            assertThat(new Installer(home, serving(archive)).install(artifact(sha256(archive)), Http.Progress.NONE)
                    .removedLeftovers()).containsExactly(theirs);
        } finally {
            other.destroyForcibly();
        }
    }
}
