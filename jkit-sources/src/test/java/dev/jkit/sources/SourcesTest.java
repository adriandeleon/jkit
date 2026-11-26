package dev.jkit.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.FoojaySource.Pkg;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SourcesTest {

    /** Answers by the longest route whose text the URL contains (so overlapping keys are stable); else 404. */
    static Http fake(Map<String, String> routes) {
        return new Http() {
            @Override
            public String get(URI uri) throws IOException {
                return routes.entrySet().stream()
                        .filter(e -> uri.toString().contains(e.getKey()))
                        .max(Comparator.comparingInt(e -> e.getKey().length()))
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
            {"result":[{"direct_download_uri":"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz","checksum":"abc123",
            "checksum_type":"sha256","checksum_uri":""}]}""";

    @Test
    void resolvesNewestGlibcBuildWithoutVariants() throws IOException {
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", INFO)),
                FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.version().raw()).isEqualTo("21.0.13+9");
        assertThat(a.url()).hasToString("https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz");
        assertThat(a.checksumAlgorithm()).isEqualTo("sha256");
        assertThat(a.checksum()).isEqualTo("abc123");
        // none listed, but Temurin signs every 21 build: jkit takes the .sig next to the download
        assertThat(a.signature()).hasToString("https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz.sig");
    }

    /** What install finds for one build of {@code vendor} whose foojay answer is {@code info}. */
    private static Artifact one(String vendor, String version, String info) throws IOException {
        // listed under the download's own file name, as foojay lists them
        Matcher link = Pattern.compile("\"direct_download_uri\":\"[^\"?#]*/([A-Za-z0-9][^\"/?#]*)[\"?#]").matcher(info);
        String builds = "{\"result\":[" + pkg("b", vendor, Version.parse(version).part(0), version, "glibc", "")
                .replace("\"b.tar.gz\"", "\"" + (link.find() ? link.group(1) : "b.tar.gz") + "\"") + "]}";
        return new FoojaySource(fake(Map.of("jdk_version=", builds, "ids/b", info)), FoojaySource.DEFAULT_BASE)
                .resolve(Tool.JDK, version, Vendor.of(vendor), Platform.LINUX_X64, false, false);
    }

    private static String info(String download, String signature) {
        return """
                {"result":[{"direct_download_uri":"%s","checksum":"abc123","checksum_type":"sha256",
                "signature_uri":"%s"}]}""".formatted(download, signature);
    }

    @Test
    void theSignatureOfAVendorJkitHasKeysForDoesntDependOnFoojayListingIt() throws IOException {
        // the real links: foojay lists them for most builds, and leaves them out of some
        String temurin = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/"
                + "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz";
        String microsoft = "https://aka.ms/download-jdk/microsoft-jdk-21.0.13.1-linux-x64.tar.gz";
        String corretto = "https://corretto.aws/downloads/resources/21.0.13.1.2/amazon-corretto-21.0.13.1.2-linux-x64.tar.gz";
        for (String listed : new String[] {"", "x"}) {
            assertThat(one("temurin", "21.0.13+9", info(temurin, listed.isEmpty() ? "" : temurin + ".sig")).signature())
                    .hasToString(temurin + ".sig");
            assertThat(one("microsoft", "21.0.13.1", info(microsoft, listed.isEmpty() ? "" : microsoft + ".sig"))
                    .signature()).hasToString(microsoft + ".sig");
            assertThat(one("corretto", "21.0.13.1+2", info(corretto, "")).signature()).hasToString(corretto + ".sig");
        }
        // the signature is looked for next to the download, on the vendor's server; a foojay that
        // lists another link, or a plain http one, doesn't change where jkit looks
        assertThat(one("temurin", "21.0.13+9", info(temurin, "http://example.invalid/b.sig")).signature())
                .hasToString(temurin + ".sig");
        // a link with no file name to add .sig to, or one that names it in a query, has no
        // signature jkit can find: refused, rather than installed on foojay's checksum
        for (String odd : new String[] {"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz?x=1",
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/",
                "https://aka.ms/download-jdk/?file=microsoft-jdk-21.0.13.1-linux-x64.tar.gz"}) {
            String vendor = odd.contains("aka.ms") ? "microsoft" : "temurin";
            assertThatThrownBy(() -> one(vendor, vendor.equals("temurin") ? "21.0.13+9" : "21.0.13.1", info(odd, "")))
                    .as(odd).isInstanceOf(JkitException.class)
                    .hasMessageContaining("refusing it");
        }
        assertThat(FoojaySource.nextTo(URI.create("https://aka.ms"))).isNull();
        assertThat(FoojaySource.nextTo(URI.create("https://aka.ms/a/b.zip"))).hasToString("https://aka.ms/a/b.zip.sig");
    }

    @Test
    void aSigningVendorsDownloadMustBeOnThatVendorsServer() throws IOException {
        // every build foojay lists for them is there; a foojay that points one elsewhere is refused,
        // so a Temurin build from before Adoptium signed still comes from Adoptium
        String releases = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/";
        assertThat(one("temurin", "17.0.3+7", info(releases + "b.tar.gz", "")).url()).hasToString(releases + "b.tar.gz");
        for (String elsewhere : new String[] {"https://example.invalid/b.tar.gz",
                "https://github.com/someone/temurin17-binaries/releases/download/jdk-17.0.3%2B7/b.tar.gz",
                "https://github.com/adoptium/../x/b.tar.gz", "https://github.com/adoptium/%2e%2e/x/b.tar.gz",
                "https://github.com:8443/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/b.tar.gz",
                "https://x@github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/b.tar.gz",
                "https://github.com.example.invalid/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/b.tar.gz",
                // github.com serves a fork's commits from the original repository's path too
                "https://github.com/adoptium/temurin17-binaries/archive/0123456789abcdef0123456789abcdef01234567.tar.gz",
                "https://github.com/adoptium/jdk17u/archive/b.tar.gz",
                "https://github.com/adoptium/temurin17-binaries/raw/main/b.tar.gz",
                "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/x/b.tar.gz",
                releases + "b.tar.gz#x"}) {
            assertThatThrownBy(() -> one("temurin", "17.0.3+7", info(elsewhere, "")))
                    .as(elsewhere).isInstanceOf(JkitException.class)
                    .hasMessageContaining("not on Adoptium's GitHub releases (github.com/adoptium/temurin<N>-binaries/"
                            + "releases/); refusing it");
        }
        // the file must be the build's own: a release asset of another name is another file
        String listed = "{\"result\":[" + pkg("b", "temurin", 17, "17.0.3+7", "glibc", "") + "]}";
        assertThatThrownBy(() -> new FoojaySource(fake(Map.of("jdk_version=", listed, "ids/b", info(releases + "c.tar.gz", ""))),
                FoojaySource.DEFAULT_BASE).resolve(Tool.JDK, "17", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .hasMessageContaining("refusing it");
        assertThatThrownBy(() -> one("microsoft", "21.0.13.1", info("https://example.invalid/m.tar.gz", "")))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("at https://example.invalid/m.tar.gz, not on aka.ms/download-jdk/");
        assertThatThrownBy(() -> one("microsoft", "21.0.13.1", info("https://aka.ms/other/m.tar.gz", "")))
                .isInstanceOf(JkitException.class).hasMessageContaining("not on aka.ms/download-jdk/");
        for (String elsewhere : new String[] {"https://corretto.aws.example.invalid/c.tar.gz",
                "https://corretto.aws/c.tar.gz", "https://corretto.aws/downloads/c.tar.gz",
                "https://d3pxv6yz143wms.cloudfront.net/a/b/c.tar.gz"}) {
            assertThatThrownBy(() -> one("corretto", "21.0.13.1+2", info(elsewhere, "")))
                    .as(elsewhere).isInstanceOf(JkitException.class).hasMessageContaining("not on corretto.aws");
        }
        String corretto = "https://corretto.aws/downloads/resources/21.0.13.1.2/amazon-corretto-21.0.13.1.2-linux-x64.tar.gz";
        assertThat(one("corretto", "21.0.13.1+2", info(corretto, "")).url()).hasToString(corretto);
        String cloudfront = "https://d3pxv6yz143wms.cloudfront.net/8.232.09.1/amazon-corretto-8.232.09.1-linux-x64.tar.gz";
        assertThat(one("corretto", "8.0.232+9", info(cloudfront, "")).url()).hasToString(cloudfront);
        // search asks too, and says the same
        Pkg b = new Pkg("b", Vendor.of("temurin"), 21, Version.parse("21.0.13+9"), "b.tar.gz", "tar.gz", "lts", true);
        assertThatThrownBy(() -> new FoojaySource(fake(Map.of("ids/b", info("https://example.invalid/b.tar.gz", ""))),
                FoojaySource.DEFAULT_BASE).availability(b)).hasMessageContaining("refusing it");
        // other vendors' downloads are wherever they host them; a mirror on this machine serves its own
        assertThat(one("zulu", "21.0.13", info("https://example.invalid/z.tar.gz", "")).url().getHost())
                .isEqualTo("example.invalid");
        String builds = "{\"result\":[" + pkg("b", "temurin", 21, "21.0.13+9", "glibc", "") + "]}";
        assertThat(new FoojaySource(fake(Map.of("jdk_version=", builds, "ids/b", info("http://127.0.0.1:9/b.tar.gz", ""))),
                URI.create("http://127.0.0.1:9/disco/")).resolve(Tool.JDK, "21", Vendor.of("temurin"),
                Platform.LINUX_X64, false, false).url()).hasToString("http://127.0.0.1:9/b.tar.gz");
    }

    @Test
    void correttosCatchAllLatestEntriesAreSkipped() throws IOException {
        // foojay's "8" entry links corretto.aws/downloads/latest/..., whatever build is newest that day
        String builds = "{\"result\":[" + String.join(",",
                pkg("amazon-corretto-8-x64-linux-jdk", "corretto", 8, "8", "glibc", ""),
                pkg("r", "corretto", 8, "8.0.472+8", "glibc", "")
                        .replace("r.tar.gz", "amazon-corretto-8.472.08.1-linux-x64.tar.gz")) + "]}";
        String info = info("https://corretto.aws/downloads/resources/8.472.08.1/amazon-corretto-8.472.08.1-linux-x64.tar.gz", "");
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=8", builds,
                "ids/r", info)), FoojaySource.DEFAULT_BASE);
        assertThat(foojay.builds(8, Vendor.of("corretto"), Platform.LINUX_X64, false, false))
                .extracting(p -> p.version().raw()).containsExactly("8.0.472+8");
        assertThat(foojay.resolve(Tool.JDK, "8", Vendor.of("corretto"), Platform.LINUX_X64, false, false)
                .version().raw()).isEqualTo("8.0.472+8");
        // and a /latest/ link under another name is no direct download
        String latest = info("https://corretto.aws/downloads/latest/amazon-corretto-8-x64-linux-jdk.tar.gz", "");
        Pkg b = new Pkg("b", Vendor.of("corretto"), 8, Version.parse("8.0.472+8"), "b.tar.gz", "tar.gz", "lts", true);
        assertThat(new FoojaySource(fake(Map.of("ids/b", latest)), FoojaySource.DEFAULT_BASE).availability(b)
                .downloadable()).isFalse();
        // other vendors' names are theirs
        assertThat(FoojaySource.latestCorretto(new Pkg("z", Vendor.of("zulu"), 8, Version.parse("8"),
                "amazon-corretto-8-x.tar.gz", "tar.gz", "lts", true))).isFalse();
    }

    @Test
    void onlyBuildsOfTheVendorAndMajorAskedForAreCandidates() throws IOException {
        // asked for Temurin 21, a hostile or broken list answers with builds that would skip the
        // signature check: one it calls Zulu, and Temurin builds from before Adoptium signed
        String builds = "{\"result\":[" + String.join(",",
                pkg("z", "zulu", 21, "21.0.99", "glibc", ""),
                pkg("o", "temurin", 21, "17.0.3+7", "glibc", ""),
                pkg("p", "temurin", 17, "17.0.3+7", "glibc", ""),
                pkg("b", "temurin", 21, "21.0.13+9", "glibc", "")) + "]}";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", builds, "ids/b", INFO)), FoojaySource.DEFAULT_BASE);
        assertThat(foojay.builds(21, Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .extracting(Pkg::id).containsExactly("b");
        assertThat(foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false).vendor())
                .isEqualTo(Vendor.of("temurin"));
        // nothing that is: no build, rather than one of those
        String only = "{\"result\":[" + pkg("z", "zulu", 21, "21.0.99", "glibc", "") + "]}";
        assertThatThrownBy(() -> new FoojaySource(fake(Map.of("jdk_version=21", only)), FoojaySource.DEFAULT_BASE)
                .resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class).hasMessageContaining("temurin has no build matching jdk@21");
    }

    @Test
    void temurinBuildsFromBeforeAdoptiumSignedThemHaveNone() throws IOException {
        String old = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/x.tar.gz";
        assertThat(one("temurin", "17.0.3+7", info(old, "")).signature()).isNull();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("8.0.332+9"))).isFalse();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("8.0.342+7"))).isTrue();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("11.0.15+10"))).isFalse();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("11.0.16+8"))).isTrue();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("16.0.2+7"))).isFalse();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("17.0.4+8"))).isTrue();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("18.0.1+10"))).isFalse();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("19+36"))).isTrue();
        assertThat(FoojaySource.vendorSigns(Vendor.of("temurin"), Version.parse("26.0.2.1-ea+1"))).isTrue();
        assertThat(FoojaySource.vendorSigns(Vendor.of("zulu"), Version.parse("21.0.13"))).isFalse();
        // a link foojay lists for such a build is used, https only: for a vendor jkit has keys
        // for, a plain http one is refused, not quietly dropped
        assertThat(one("temurin", "17.0.3+7", info(old, old + ".sig")).signature()).hasToString(old + ".sig");
        assertThatThrownBy(() -> one("temurin", "17.0.3+7", info(old, "http://example.invalid/x.sig")))
                .isInstanceOf(JkitException.class).hasMessageContaining("refusing a signature over http");
        // other vendors' links are never fetched: a bad one is dropped
        assertThat(one("zulu", "21.0.13", info("https://example.invalid/z.tar.gz", "http://example.invalid/z.sig"))
                .signature()).isNull();
        assertThat(one("zulu", "21.0.13", info("https://example.invalid/z.tar.gz", "https://example.invalid/z.sig"))
                .signature()).hasToString("https://example.invalid/z.sig");
    }

    @Test
    void muslPlatformResolvesMuslBuilds() throws IOException {
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/c", INFO.replace("b.tar.gz", "c.tar.gz"))),
                FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64_MUSL, false, false);
        assertThat(a.version().raw()).isEqualTo("21.0.13+9");
        assertThat(Platform.LINUX_X64.withMusl()).isEqualTo(Platform.LINUX_X64_MUSL);
        assertThat(Platform.LINUX_X64_MUSL.libc()).isEqualTo("musl");
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
                ".sha512", "d".repeat(128) + "  apache-maven-3.9.11-bin.zip")), MavenSource.DEFAULT_BASE);
        Artifact a = maven.resolve(null, false);
        assertThat(a.version().raw()).isEqualTo("3.9.11");
        assertThat(a.checksum()).isEqualTo("d".repeat(128));
        assertThat(a.signature()).hasToString(a.url() + ".asc");
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
        // signed before the release managers whose keys jkit has: no signature to check
        assertThat(a.signature()).isNull();
    }

    @Test
    void newMavenDoesNotFallBackToSha1() {
        // 3.9.1+ publishes .sha512; a mirror that lacks it must not downgrade the check to sha1
        String metadata = "<metadata><versioning><versions><version>3.9.11</version></versions></versioning></metadata>";
        MavenSource maven = new MavenSource(fake(Map.of("maven-metadata.xml", metadata,
                "apache-maven-3.9.11-bin.zip.sha1", "a".repeat(40))), MavenSource.DEFAULT_BASE);
        assertThatThrownBy(() -> maven.resolve("3.9.11", false)).hasMessageContaining("no .sha512");
    }

    @Test
    void mavenMirrorOverPlainHttpIsRefused() {
        String metadata = "<metadata><versioning><versions><version>3.9.11</version></versions></versioning></metadata>";
        MavenSource maven = new MavenSource(fake(Map.of("maven-metadata.xml", metadata,
                ".sha512", "b".repeat(128))), URI.create("http://nexus.example/maven2/"));
        assertThatThrownBy(() -> maven.resolve("3.9.11", false)).hasMessageContaining("refusing to download over http");
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
                {"result":[{"direct_download_uri":"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz","checksum":"",
                "checksum_type":"sha256","checksum_uri":"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz.checksum"}]}""";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", jetbrains,
                "b.tar.gz.checksum", sha512 + " *b.tar.gz\n")), FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.checksumAlgorithm()).isEqualTo("sha512");
        assertThat(a.checksum()).isEqualTo(sha512);

        Checksum bsd = Checksum.parse("MD5 (b.tar.gz) = " + md5 + "\n", "b.tar.gz", "sha256");
        assertThat(bsd).isEqualTo(new Checksum("md5", md5));
        Checksum many = Checksum.parse("e".repeat(64) + "  other.zip\n" + "f".repeat(64) + "  b.tar.gz\n", "b.tar.gz", null);
        assertThat(many.hash()).isEqualTo("f".repeat(64));
        assertThat(Checksum.parse("f".repeat(64) + "\n", "b.tar.gz", null).hash()).isEqualTo("f".repeat(64));
    }

    @Test
    void versionsThatCantBeFolderNamesAreDropped() throws IOException {
        // "3.9.99.:." would put "." on PATH (a PATH separator inside the install folder's name)
        String metadata = "<metadata><versioning><versions><version>3.9.11</version>"
                + "<version>3.9.99.:.</version><version>3.9.98/../x</version></versions></versioning></metadata>";
        MavenSource maven = new MavenSource(fake(Map.of("maven-metadata.xml", metadata)), MavenSource.DEFAULT_BASE);
        assertThat(maven.versions(true)).extracting(v -> v.raw()).containsExactly("3.9.11");
    }

    @Test
    void aChecksumWeakerThanItsFileNameIsRefused() {
        // a .sha512 holding an md5 must not count as even a weak checksum
        assertThatThrownBy(() -> Checksum.parse("d".repeat(32) + "  b.zip\n", "b.zip", "sha512"))
                .isInstanceOf(JkitException.class).hasMessageContaining("md5, not sha512");
        assertThatThrownBy(() -> Checksum.parseStrict("MD5 (b.zip) = " + "d".repeat(32) + "\n", "b.zip", "sha512"))
                .isInstanceOf(JkitException.class).hasMessageContaining("holds a md5 hash");
        assertThatThrownBy(() -> Checksum.of("zz" + "d".repeat(62), "sha256"))
                .isInstanceOf(JkitException.class).hasMessageContaining("isn't a hex hash");
    }

    @Test
    void checksumFileWithoutAUsableHashIsAnError() {
        // a proxy's plain-text block page must not turn into "no checksum, install unverified"
        assertThatThrownBy(() -> Checksum.parse("Access denied by cafe proxy\n", "b.tar.gz", "sha256"))
                .isInstanceOf(JkitException.class).hasMessageContaining("holds no checksum");
        // a list of other files' hashes: the first one would only fail later as a confusing mismatch
        assertThatThrownBy(() -> Checksum.parse("e".repeat(64) + "  other.zip\n", "b.tar.gz", null))
                .isInstanceOf(JkitException.class).hasMessageContaining("doesn't list b.tar.gz");
    }

    @Test
    void checksumOverPlainHttpIsRefused() {
        String info = """
                {"result":[{"direct_download_uri":"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz","checksum":"",
                "checksum_type":"sha256","checksum_uri":"http://example.invalid/b.sha256"}]}""";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", info,
                "b.sha256", "f".repeat(64))), FoojaySource.DEFAULT_BASE);
        assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("refusing a checksum over http");
    }

    @Test
    void skipsBuildsWithoutDirectDownload() throws IOException {
        String none = "{\"result\":[{\"direct_download_uri\":\"\"}]}";
        String older = "{\"result\":[{\"direct_download_uri\":\"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/a.tar.gz\",\"checksum\":\"\"}]}";
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS, "ids/b", none, "ids/a", older)),
                FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.version().raw()).isEqualTo("21.0.12+8");
        assertThat(a.checksum()).isNull();
    }

    @Test
    void manyBuildsWithoutADownloadAreSkippedUpToALimit() throws IOException {
        // Oracle lists its newer 17 updates without a download link; the newest one with a link is
        // further down the list every quarter
        List<String> pkgs = new ArrayList<>();
        Map<String, String> routes = new HashMap<>();
        String none = "{\"result\":[{\"direct_download_uri\":\"\"}]}";
        for (int u = 40; u >= 1; u--) {
            pkgs.add(pkg("o" + u, "oracle", 17, "17.0." + u, "glibc", ""));
            routes.put("ids/o" + u, none);
        }
        routes.put("jdk_version=17", "{\"result\":[" + String.join(",", pkgs) + "]}");
        routes.put("ids/o9", "{\"result\":[{\"direct_download_uri\":\"https://example.invalid/o9.tar.gz\",\"checksum\":\"\"}]}");
        Artifact a = new FoojaySource(fake(routes), FoojaySource.DEFAULT_BASE)
                .resolve(Tool.JDK, "17", Vendor.of("oracle"), Platform.LINUX_X64, false, false);
        assertThat(a.version().raw()).isEqualTo("17.0.9"); // the 32nd build looked up

        routes.put("ids/o9", none);
        assertThatThrownBy(() -> new FoojaySource(fake(routes), FoojaySource.DEFAULT_BASE)
                .resolve(Tool.JDK, "17", Vendor.of("oracle"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class).hasMessageContaining("nor do the next 31 builds")
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .contains("an older build may have one: jkit install jdk@17.0.8 oracle"));
    }

    @Test
    void availabilitySaysWhatInstallWouldFind() throws IOException {
        Pkg b = new FoojaySource(fake(Map.of("jdk_version=21", BUILDS)), FoojaySource.DEFAULT_BASE)
                .builds(21, Vendor.of("temurin"), Platform.LINUX_X64, false, false).stream()
                .filter(p -> p.id().equals("b")).findFirst().orElseThrow();
        // a checksum in foojay's answer
        FoojaySource.Availability a = new FoojaySource(fake(Map.of("ids/b", INFO)), FoojaySource.DEFAULT_BASE)
                .availability(b);
        assertThat(a.downloadable()).isTrue();
        assertThat(a.checksum()).isTrue();
        // a checksum file counts too, unread: install fetches it
        String file = """
                {"result":[{"direct_download_uri":"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz","checksum":"",
                "checksum_uri":"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz.sha256.txt"}]}""";
        assertThat(new FoojaySource(fake(Map.of("ids/b", file)), FoojaySource.DEFAULT_BASE).availability(b).checksum())
                .isTrue();
        // no checksum at all
        String bare = "{\"result\":[{\"direct_download_uri\":\"https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz\",\"checksum\":\"\"}]}";
        a = new FoojaySource(fake(Map.of("ids/b", bare)), FoojaySource.DEFAULT_BASE).availability(b);
        assertThat(a.downloadable()).isTrue();
        assertThat(a.checksum()).isFalse();
        // but Temurin signs every 21 build: install checks that instead
        assertThat(a.signature()).hasToString("https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/b.tar.gz.sig");
        // a Corretto download has its .sig
        String corretto = """
                {"result":[{"direct_download_uri":"https://corretto.aws/downloads/resources/21.0.13.9.1/b.tar.gz",
                "checksum":""}]}""";
        Pkg c = new Pkg("b", Vendor.of("corretto"), 21, Version.parse("21.0.13.9.1"), "b.tar.gz", "tar.gz", "lts", true);
        assertThat(new FoojaySource(fake(Map.of("ids/b", corretto)), FoojaySource.DEFAULT_BASE).availability(c)
                .signature()).hasToString("https://corretto.aws/downloads/resources/21.0.13.9.1/b.tar.gz.sig");
        // no link
        assertThat(new FoojaySource(fake(Map.of("ids/b", "{\"result\":[{\"direct_download_uri\":\"\"}]}")),
                FoojaySource.DEFAULT_BASE).availability(b).downloadable()).isFalse();
    }

    @Test
    void availabilityLooksFurtherWhenTheNewestHasNoDownloadLikeInstallDoes() throws IOException {
        List<String> pkgs = new ArrayList<>();
        Map<String, String> routes = new HashMap<>();
        String none = "{\"result\":[{\"direct_download_uri\":\"\"}]}";
        for (int u = 40; u >= 1; u--) {
            pkgs.add(pkg("o" + u, "oracle", 17, "17.0." + u, "glibc", ""));
            routes.put("ids/o" + u, none);
        }
        routes.put("jdk_version=17", "{\"result\":[" + String.join(",", pkgs) + "]}");
        routes.put("ids/o9", "{\"result\":[{\"direct_download_uri\":\"https://example.invalid/o9.tar.gz\",\"checksum\":\"\"}]}");
        FoojaySource foojay = new FoojaySource(fake(routes), FoojaySource.DEFAULT_BASE);
        Pkg newest = foojay.builds(17, Vendor.of("oracle"), Platform.LINUX_X64, false, false).getFirst();
        FoojaySource.Availability a = foojay.availability(newest, "17", Platform.LINUX_X64, false, false);
        assertThat(a.pkg().version().raw()).isEqualTo("17.0.9"); // the build install would pick
        assertThat(a.checksum()).isFalse();

        routes.put("ids/o9", none);
        routes.put("ids/o8", "{\"result\":[{\"direct_download_uri\":\"https://example.invalid/o8.tar.gz\"}]}");
        // install gives up after 32 builds, and so does search
        assertThat(new FoojaySource(fake(routes), FoojaySource.DEFAULT_BASE)
                .availability(newest, "17", Platform.LINUX_X64, false, false).downloadable()).isFalse();
    }

    @Test
    void buildsListedAsNotDirectlyDownloadableAreNotLookedUp() throws IOException {
        String list = "{\"result\":[" + pkg("o2", "oracle", 11, "11.0.2", "glibc", "")
                .replace("\"feature\"", "\"directly_downloadable\":false,\"feature\"") + "]}";
        // no ids/ route: a lookup would be a 404
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=11", list)), FoojaySource.DEFAULT_BASE);
        Pkg newest = foojay.builds(11, Vendor.of("oracle"), Platform.LINUX_X64, false, false).getFirst();
        assertThat(newest.directlyDownloadable()).isFalse();
        assertThat(foojay.availability(newest, null, Platform.LINUX_X64, false, false).downloadable()).isFalse();
    }

    @Test
    void gradleTreatsOldMilestonesAsPreReleases() throws IOException {
        String all = """
                [{"version":"8.10.2","downloadUrl":"https://example.invalid/gradle-8.10.2-bin.zip","checksum":"%s",
                  "checksumUrl":"","rcFor":"","milestoneFor":""},
                 {"version":"1.0-milestone-9","downloadUrl":"https://example.invalid/gradle-1.0-milestone-9-bin.zip",
                  "checksum":"","checksumUrl":"","rcFor":"","milestoneFor":""}]""".formatted("a".repeat(64));
        GradleSource gradle = new GradleSource(fake(Map.of("versions/all", all)), GradleSource.DEFAULT_BASE);
        assertThat(gradle.versions(false)).extracting(Version::raw).containsExactly("8.10.2");
        assertThat(gradle.resolve("8", false).checksumAlgorithm()).isEqualTo("sha256");
    }

    private static final String SBT_METADATA = """
            <metadata><versioning><versions>
              <version>0.99.4</version><version>1.2.8</version><version>1.3.0-M5-94d5ec</version>
              <version>1.3.0</version><version>1.10.7</version><version>1.12.0-M1</version>
              <version>2.0-RC3</version><version>2.1.0-RC1</version><version>2.1.0</version>
            </versions></versioning></metadata>""";

    @Test
    void sbtListsReleasesWithZipsNewestFirst() throws IOException {
        SbtSource sbt = new SbtSource(fake(Map.of("org/scala-sbt/sbt/maven-metadata.xml", SBT_METADATA)),
                MavenSource.DEFAULT_BASE, SbtSource.DEFAULT_DOWNLOADS);
        assertThat(sbt.versions(false)).extracting(Version::raw)
                .containsExactly("2.1.0", "1.10.7", "1.3.0");
        assertThat(sbt.versions(true)).extracting(Version::raw)
                .containsExactly("2.1.0", "2.1.0-RC1", "1.10.7", "1.3.0");
    }

    @Test
    void sbtResolvesTheGitHubZipAndItsSha256() throws IOException {
        String hash = "4f97451fa04c6ff94f00128382daac2703886611f09a5ad7e22f30789797cb59";
        SbtSource sbt = new SbtSource(fake(Map.of(
                "org/scala-sbt/sbt/maven-metadata.xml", SBT_METADATA,
                "v1.10.7/sbt-1.10.7.zip.sha256", hash + "  sbt-1.10.7.zip\n")),
                MavenSource.DEFAULT_BASE, SbtSource.DEFAULT_DOWNLOADS);
        Artifact a = sbt.resolve("1", false);
        assertThat(a.tool()).isEqualTo(Tool.SBT);
        assertThat(a.url()).hasToString("https://github.com/sbt/sbt/releases/download/v1.10.7/sbt-1.10.7.zip");
        assertThat(a.fileName()).isEqualTo("sbt-1.10.7.zip");
        assertThat(a.checksumAlgorithm()).isEqualTo("sha256");
        assertThat(a.checksum()).isEqualTo(hash);
        assertThat(a.signature())
                .hasToString("https://github.com/sbt/sbt/releases/download/v1.10.7/sbt-1.10.7.zip.asc");
    }

    @Test
    void sbtWithoutAChecksumFileIsUnverifiedNotAnError() throws IOException {
        SbtSource sbt = new SbtSource(fake(Map.of("org/scala-sbt/sbt/maven-metadata.xml", SBT_METADATA)),
                MavenSource.DEFAULT_BASE, SbtSource.DEFAULT_DOWNLOADS);
        Artifact a = sbt.resolve("1.3", false);
        assertThat(a.version().raw()).isEqualTo("1.3.0");
        assertThat(a.checksum()).isNull();
    }

    @Test
    void sbtSaysWhenOnlyAPreReleaseMatchesOrTheVersionIsTooOld() {
        SbtSource sbt = new SbtSource(fake(Map.of("org/scala-sbt/sbt/maven-metadata.xml", SBT_METADATA)),
                MavenSource.DEFAULT_BASE, SbtSource.DEFAULT_DOWNLOADS);
        assertThatThrownBy(() -> sbt.resolve("2.1.0-RC1", false))
                .isInstanceOf(JkitException.class).hasMessageContaining("only pre-releases");
        assertThatThrownBy(() -> sbt.resolve("1.2", false))
                .isInstanceOf(JkitException.class).hasMessageContaining("no sbt release matches 1.2");
    }
}
