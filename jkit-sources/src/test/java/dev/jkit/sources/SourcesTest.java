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

    /** Answers by the first route whose text the URL contains. */
    private static Http fake(Map<String, String> routes) {
        return new Http() {
            @Override
            public String get(URI uri) throws IOException {
                return routes.entrySet().stream()
                        .filter(e -> uri.toString().contains(e.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElseThrow(() -> new IOException("no fixture for " + uri));
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
}
