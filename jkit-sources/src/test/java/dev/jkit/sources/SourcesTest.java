package dev.jkit.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourcesTest {

    /** Answers by the longest route whose text the URL contains (so overlapping keys are stable); else 404. */
    static Http fake(Map<String, String> routes) {
        return new Http() {
            @Override
            public String get(URI uri) throws IOException {
                return routes.entrySet().stream()
                        .filter(e -> uri.toString().contains(e.getKey()))
                        .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                        .map(Map.Entry::getValue)
                        .orElseThrow(() -> new HttpStatusException(404, uri));
            }

            @Override
            public void download(URI uri, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static String pkg(String id, String dist, int major, String version, String libc, String feature) {
        return """
                {"id":"%s","distribution":"%s","major_version":%d,"java_version":"%s","lib_c_type":"%s",
                 "archive_type":"tar.gz","filename":"%s.tar.gz","javafx_bundled":false,"term_of_support":"lts",
                 "feature":[%s]}""".formatted(id, dist, major, version, libc, id, feature);
    }

    private static final String BUILDS = "{\"result\":[" + String.join(",",
            pkg("a", "temurin", 21, "21.0.12+8", "glibc", ""),
            pkg("b", "temurin", 21, "21.0.13+9", "glibc", ""),
            pkg("c", "temurin", 21, "21.0.13+9", "musl", ""),
            pkg("d", "temurin", 21, "21.0.14+1", "glibc", "\"crac\"")) + "]}";

    private static final String INFO = """
            {"result":[{"direct_download_uri":"https://example.invalid/b.tar.gz","checksum":"abc123",
            "checksum_type":"sha256","checksum_uri":""}]}""";

    @Test
    void resolvesNewestGlibcBuildWithoutVariants() throws IOException {
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", INFO)),
                FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.version().raw()).isEqualTo("21.0.13+9");
        assertThat(a.url()).hasToString("https://example.invalid/b.tar.gz");
        assertThat(a.checksumAlgorithm()).isEqualTo("sha256");
        assertThat(a.checksum()).isEqualTo("abc123");
    }

    @Test
    void noMatchNamesVendorsThatHaveTheMajor() {
        String index = "{\"result\":[" + pkg("z", "zulu", 22, "22.0.2+9", "glibc", "") + "]}";
        FoojaySource foojay = new FoojaySource(fake(Map.of(
                "jdk_version=22", "{\"result\":[]}", "latest=available", index)), FoojaySource.DEFAULT_BASE);
        assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "22", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("temurin has no build matching jdk@22")
                .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly("available from: zulu"));
    }

    private static final String DISTRIBUTIONS = """
            {"result":[
              {"api_parameter":"corretto","synonyms":["corretto","Corretto"]},
              {"api_parameter":"sap_machine","synonyms":["sap_machine","sapmachine","SAP Machine"]},
              {"api_parameter":"aoj","synonyms":["aoj"]}]}""";

    @Test
    void knownVendorsAndSynonymsPass() throws IOException {
        FoojaySource foojay = new FoojaySource(fake(Map.of("distributions", DISTRIBUTIONS)), FoojaySource.DEFAULT_BASE);
        assertThat(foojay.requireKnown(Vendor.of("corretto"), "corretto", "").foojayName()).isEqualTo("corretto");
        assertThat(foojay.requireKnown(Vendor.of("SAP Machine"), "SAP Machine", "").name()).isEqualTo("sapmachine");
    }

    @Test
    void typoGetsDidYouMeanAndRetiredVendorsAreHidden() {
        FoojaySource foojay = new FoojaySource(fake(Map.of("distributions", DISTRIBUTIONS)), FoojaySource.DEFAULT_BASE);
        assertThatThrownBy(() -> foojay.requireKnown(Vendor.of("correto"), "correto", ""))
                .isInstanceOf(JkitException.class)
                .hasMessage("unknown JDK vendor 'correto'")
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .containsExactly("did you mean: corretto?", "vendors: corretto, sapmachine"));
        assertThatThrownBy(() -> foojay.requireKnown(Vendor.of("zuul"), "zuul", "your default JDK vendor (jdk.vendor)"))
                .hasMessage("your default JDK vendor (jdk.vendor) 'zuul' is not a known JDK vendor");
    }

    @Test
    void mavenSkipsPreReleasesAndPointsToThem() throws IOException {
        String metadata = "<metadata><versioning><versions><version>3.9.11</version>"
                + "<version>4.0.0-rc-7</version></versions></versioning></metadata>";
        MavenSource maven = new MavenSource(fake(Map.of("maven-metadata.xml", metadata,
                ".sha512", "deadbeef  apache-maven-3.9.11-bin.zip")), MavenSource.DEFAULT_BASE);
        Artifact a = maven.resolve(null, false);
        assertThat(a.version().raw()).isEqualTo("3.9.11");
        assertThat(a.checksum()).isEqualTo("deadbeef");
        assertThatThrownBy(() -> maven.resolve("4", false))
                .hasMessageContaining("only pre-releases match maven@4: 4.0.0-rc-7");
    }

    @Test
    void oldMavenFallsBackToSha1() throws IOException {
        String metadata = "<metadata><versioning><versions><version>3.8.8</version></versions></versioning></metadata>";
        String sha1 = "a".repeat(40);
        MavenSource maven = new MavenSource(fake(Map.of("maven-metadata.xml", metadata,
                "apache-maven-3.8.8-bin.zip.sha1", sha1)), MavenSource.DEFAULT_BASE);
        Artifact a = maven.resolve("3.8.8", false);
        assertThat(a.checksumAlgorithm()).isEqualTo("sha1");
        assertThat(a.checksum()).isEqualTo(sha1);
    }

    @Test
    void exactPreReleaseSpecPicksThatPreRelease() throws IOException {
        String metadata = "<metadata><versioning><versions><version>4.0.0-rc-4</version>"
                + "<version>4.0.0-rc-7</version><version>4.0.0-rc-10</version></versions></versioning></metadata>";
        MavenSource maven = new MavenSource(fake(Map.of("maven-metadata.xml", metadata,
                ".sha512", "b".repeat(128))), MavenSource.DEFAULT_BASE);
        assertThat(maven.resolve("4.0.0-rc-4", true).version().raw()).isEqualTo("4.0.0-rc-4");
        assertThat(maven.resolve("4", true).version().raw()).isEqualTo("4.0.0-rc-10"); // rc-10 is newer than rc-7
    }

    @Test
    void checksumUriKeepsTheRealAlgorithmAndReadsBsdFiles() throws IOException {
        String sha512 = "c".repeat(128);
        String md5 = "d".repeat(32);
        String jetbrains = """
                {"result":[{"direct_download_uri":"https://example.invalid/b.tar.gz","checksum":"",
                "checksum_type":"sha256","checksum_uri":"https://example.invalid/b.tar.gz.checksum"}]}""";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", jetbrains,
                "b.tar.gz.checksum", sha512 + " *b.tar.gz\n")), FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.checksumAlgorithm()).isEqualTo("sha512");
        assertThat(a.checksum()).isEqualTo(sha512);

        Checksum bsd = Checksum.parse("MD5 (b.tar.gz) = " + md5 + "\n", "b.tar.gz", "sha256");
        assertThat(bsd).isEqualTo(new Checksum("md5", md5));
        Checksum many = Checksum.parse("e".repeat(64) + "  other.zip\n" + "f".repeat(64) + "  b.tar.gz\n", "b.tar.gz", null);
        assertThat(many.hash()).isEqualTo("f".repeat(64));
    }

    @Test
    void checksumOverPlainHttpIsRefused() {
        String info = """
                {"result":[{"direct_download_uri":"https://example.invalid/b.tar.gz","checksum":"",
                "checksum_type":"sha256","checksum_uri":"http://example.invalid/b.sha256"}]}""";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", info,
                "b.sha256", "f".repeat(64))), FoojaySource.DEFAULT_BASE);
        assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("refusing to download over http");
    }

    @Test
    void skipsBuildsWithoutDirectDownload() throws IOException {
        String none = "{\"result\":[{\"direct_download_uri\":\"\"}]}";
        String older = "{\"result\":[{\"direct_download_uri\":\"https://example.invalid/a.tar.gz\",\"checksum\":\"\"}]}";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", none, "ids/a", older)),
                FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.version().raw()).isEqualTo("21.0.12+8");
        assertThat(a.checksum()).isNull();
    }

    @Test
    void gradleTreatsOldMilestonesAsPreReleases() throws IOException {
        String all = """
                [{"version":"8.10.2","downloadUrl":"https://example.invalid/gradle-8.10.2-bin.zip","checksum":"%s",
                  "checksumUrl":"","rcFor":"","milestoneFor":""},
                 {"version":"1.0-milestone-9","downloadUrl":"https://example.invalid/gradle-1.0-milestone-9-bin.zip",
                  "checksum":"","checksumUrl":"","rcFor":"","milestoneFor":""}]""".formatted("a".repeat(64));
        GradleSource gradle = new GradleSource(fake(Map.of("versions/all", all)), GradleSource.DEFAULT_BASE);
        assertThat(gradle.versions(false)).extracting(dev.jkit.model.Version::raw).containsExactly("8.10.2");
        assertThat(gradle.resolve("8", false).checksumAlgorithm()).isEqualTo("sha256");
    }
}
