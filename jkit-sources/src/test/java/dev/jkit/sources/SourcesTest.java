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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
        return one(FoojaySource.DEFAULT_BASE, vendor, version, info);
    }

    /** The same, through the foojay at {@code base}. */
    private static Artifact one(URI base, String vendor, String version, String info) throws IOException {
        // listed under the download's own file name, as foojay lists them
        Matcher link = Pattern.compile("\"direct_download_uri\":\"[^\"?#]*/([A-Za-z0-9][^\"/?#]*)[\"?#]").matcher(info);
        String builds = "{\"result\":[" + pkg("b", vendor, Version.parse(version).part(0), version, "glibc", "")
                .replace("\"b.tar.gz\"", "\"" + (link.find() ? link.group(1) : "b.tar.gz") + "\"") + "]}";
        return new FoojaySource(fake(Map.of("jdk_version=", builds, "ids/b", info)), base)
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
        // vendors whose servers aren't known are downloaded wherever foojay says; a mirror on this
        // machine serves its own
        assertThat(one("kona", "21.0.13", info("https://example.invalid/k.tar.gz", "")).url().getHost())
                .isEqualTo("example.invalid");
        String builds = "{\"result\":[" + pkg("b", "temurin", 21, "21.0.13+9", "glibc", "") + "]}";
        assertThat(new FoojaySource(fake(Map.of("jdk_version=", builds, "ids/b", info("http://127.0.0.1:9/b.tar.gz", ""))),
                URI.create("http://127.0.0.1:9/disco/")).resolve(Tool.JDK, "21", Vendor.of("temurin"),
                Platform.LINUX_X64, false, false).url()).hasToString("http://127.0.0.1:9/b.tar.gz");
    }

    @Test
    void aFoojayMirrorMayServeTheBuildsAVendorSignedButNotTheOthers() throws IOException {
        // a company's own foojay, which rehosts the files: a signed build is checked by its
        // signature, next to the mirror's copy, and its version, wherever it comes from
        URI mirror = URI.create("https://foojay.corp.example/disco/v3.0");
        String temurin = "https://files.corp.example/jdk/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz";
        assertThat(one(mirror, "temurin", "21.0.13+9", info(temurin, "")).signature()).hasToString(temurin + ".sig");
        String microsoft = "https://files.corp.example/jdk/microsoft-jdk-21.0.13.1-linux-x64.tar.gz";
        assertThat(one(mirror, "microsoft", "21.0.13.1", info(microsoft, "")).signature()).hasToString(microsoft + ".sig");
        String corretto = "https://files.corp.example/jdk/amazon-corretto-21.0.13.1.2-linux-x64.tar.gz";
        assertThat(one(mirror, "corretto", "21.0.13.1+2", info(corretto, "")).signature()).hasToString(corretto + ".sig");
        // a Temurin build from before Adoptium signed has only the mirror's checksum: Adoptium's links only
        String old = "https://files.corp.example/jdk/OpenJDK17U-jdk_x64_linux_hotspot_17.0.3_7.tar.gz";
        assertThatThrownBy(() -> one(mirror, "temurin", "17.0.3+7", info(old, "")))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("foojay.corp.example lists the download of temurin 17.0.3+7 at " + old)
                .satisfies(e -> assertThat(((JkitException) e).hints()).singleElement().asString()
                        .contains("your foojay mirror (JKIT_SOURCES_FOOJAY_URL) must keep their links"));
        String adoptium = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/"
                + "OpenJDK17U-jdk_x64_linux_hotspot_17.0.3_7.tar.gz";
        assertThat(one(mirror, "temurin", "17.0.3+7", info(adoptium, "")).url()).hasToString(adoptium);
        // a signed build still needs a .sig jkit can find
        assertThatThrownBy(() -> one(mirror, "temurin", "21.0.13+9", info("https://files.corp.example/get?id=1", "")))
                .hasMessageContaining("where jkit can't find its signature")
                .satisfies(e -> assertThat(((JkitException) e).hints()).singleElement().asString()
                        .contains("JKIT_SOURCES_FOOJAY_URL"));
        // the default foojay keeps every build of these vendors on the vendor's server
        assertThatThrownBy(() -> one("temurin", "21.0.13+9", info(temurin, ""))).hasMessageContaining("refusing it")
                .satisfies(e -> assertThat(((JkitException) e).hints()).singleElement().asString()
                        .doesNotContain("JKIT_SOURCES_FOOJAY_URL"));
    }

    /** A build as foojay lists it, with its distribution_version and file name. */
    private static String build(String id, String dist, int major, String version, String own, String file) {
        return pkg(id, dist, major, version, "glibc", "").replace("\"" + id + ".tar.gz\"", "\"" + file + "\"")
                .replace("\"feature\"", "\"distribution_version\":\"" + own + "\",\"feature\"");
    }

    @Test
    void aRespinOfAVersionIsTheVendorsNewestNotWhicheverIsListedFirst() throws IOException {
        // foojay lists both Corretto 11.0.20 builds as 11.0.20, distribution_version included;
        // only the file name says which is the respin
        String older = build("c8", "corretto", 11, "11.0.20", "11.0.20", "amazon-corretto-11.0.20.8.1-linux-x64.tar.gz");
        String newer = build("c9", "corretto", 11, "11.0.20", "11.0.20", "amazon-corretto-11.0.20.9.1-linux-x64.tar.gz");
        String zulu15 = build("z15", "zulu", 21, "21.0.11+10", "21.50.15", "zulu21.50.15-ca-jdk21.0.11-linux_x64.tar.gz");
        String zulu19 = build("z19", "zulu", 21, "21.0.11+10", "21.50.19", "zulu21.50.19-ca-jdk21.0.11-linux_x64.tar.gz");
        for (List<String> order : List.of(List.of(older, newer, zulu15, zulu19), List.of(newer, older, zulu19, zulu15))) {
            FoojaySource foojay = new FoojaySource(fake(Map.of("latest=available",
                    "{\"result\":[" + String.join(",", order) + "]}")), FoojaySource.DEFAULT_BASE);
            assertThat(foojay.newestPerVendorAndMajor(Platform.LINUX_X64, false)).extracting(Pkg::id)
                    .containsExactlyInAnyOrder("c9", "z19");
        }
        assertThat(FoojaySource.respin("corretto", "amazon-corretto-8.242.08.1-linux-x64.tar.gz", "8.0.242"))
                .isEqualTo("8.242.08.1");
        assertThat(FoojaySource.respin("zulu", "zulu21.50.19-ca-jdk21.0.11-linux_x64.tar.gz", "21.50.19"))
                .isEqualTo("21.50.19");
    }

    @Test
    void mandrelBuildsOfOneFeatureReleaseAreToldApartByMandrelsOwnRelease() throws IOException {
        // foojay gives every Mandrel build for Java 21 the java_version "21"
        String builds = "{\"result\":[" + String.join(",",
                build("m3", "mandrel", 21, "21", "23.1.3", "mandrel-java21-linux-amd64-23.1.3.0-Final.tar.gz"),
                build("m5", "mandrel", 21, "21", "23.1.5", "mandrel-java21-linux-amd64-23.1.5.0-Final.tar.gz"),
                build("m4", "mandrel", 21, "21", "23.1.4", "mandrel-java21-linux-amd64-23.1.4.0-Final.tar.gz")) + "]}";
        String info = info("https://github.com/graalvm/mandrel/releases/download/mandrel-23.1.5.0-Final/"
                + "mandrel-java21-linux-amd64-23.1.5.0-Final.tar.gz", "");
        FoojaySource foojay = new FoojaySource(fake(Map.of("jdk_version=21", builds, "ids/m5", info)),
                FoojaySource.DEFAULT_BASE);
        assertThat(foojay.builds(21, Vendor.of("mandrel"), Platform.LINUX_X64, false, false))
                .extracting(p -> p.version().raw()).containsExactly("21+23.1.5", "21+23.1.4", "21+23.1.3");
        Artifact a = foojay.resolve(Tool.GRAALVM, "21", Vendor.of("mandrel"), Platform.LINUX_X64, false, false);
        assertThat(a.version().raw()).isEqualTo("21+23.1.5");
        assertThat(a.signature()).isNull(); // Mandrel isn't signed: no release-file check either
        // an install made when all of them were "21" is older than each, so upgrade offers the newest
        assertThat(Version.parse("21")).isLessThan(Version.parse("21+23.1.3"));
        assertThat(Version.parse("21+23.1.5").matches("21+23.1.5")).isTrue();
        assertThat(Version.parse("21+23.1.5").matches("21+23.1.4")).isFalse();
        // others keep foojay's java_version
        assertThat(FoojaySource.version("temurin", "21", "21.0.0")).isEqualTo("21");
        assertThat(FoojaySource.version("mandrel", "21", "")).isEqualTo("21");
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
        String zulu = "https://cdn.azul.com/zulu/bin/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz";
        assertThat(one("zulu", "21.0.13", info(zulu, "http://example.invalid/z.sig")).signature()).isNull();
        assertThat(one("zulu", "21.0.13", info(zulu, "https://example.invalid/z.sig"))
                .signature()).hasToString("https://example.invalid/z.sig");
    }

    @Test
    void everySigningVendorHasItsSignedBuildsAndServerKnown() {
        // a vendor added to SIGNING_VENDORS without the rules below would fail closed or open
        for (String vendor : FoojaySource.SIGNING_VENDORS) {
            assertThat(FoojaySource.vendorSigns(Vendor.fromFoojay(vendor), Version.parse("21.0.9"))).as(vendor).isTrue();
            assertThat(FoojaySource.vendorHost(vendor)).as(vendor).isNotBlank();
        }
        assertThat(FoojaySource.SIGNING_VENDORS).containsAll(FoojaySource.RETIRED_KEY_HOSTS.keySet());
        assertThat(FoojaySource.onVendorHost("temurin", URI.create(
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/x.tar.gz"), "x.tar.gz")).isTrue();
        assertThat(FoojaySource.onVendorHost("microsoft", URI.create("https://aka.ms/download-jdk/x.tar.gz"),
                "x.tar.gz")).isTrue();
        assertThat(FoojaySource.onVendorHost("corretto", URI.create(
                "https://corretto.aws/downloads/resources/21.0.9.10.1/x.tar.gz"), "x.tar.gz")).isTrue();
        assertThat(FoojaySource.PINNED_VENDORS).containsAll(FoojaySource.SIGNING_VENDORS);
    }

    /** A file next to this class in the test resources, as text. */
    static String resource(String name) throws IOException {
        try (var in = SourcesTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theRealDownloadLinksOfEveryVendorWithAKnownServerPass() throws IOException {
        // links foojay listed, file names as it listed them (some with + where the link has %2B),
        // across versions, platforms, JavaFX and Oracle's gds.oracle.com links: a pin these don't
        // pass would break installs of that vendor
        List<String[]> links = resource("foojay-download-links.txt").lines()
                .filter(l -> !l.startsWith("#") && !l.isBlank()).map(l -> l.split(" ")).toList();
        assertThat(links).hasSizeGreaterThan(300);
        TreeSet<String> vendors = new TreeSet<>();
        for (String[] link : links) {
            vendors.add(link[0]);
            assertThat(FoojaySource.onVendorHost(link[0], URI.create(link[2]), link[1])).as(String.join(" ", link)).isTrue();
            if (!link[2].endsWith("/content")) {
                // another file name is another file
                assertThat(FoojaySource.onVendorHost(link[0], URI.create(link[2]), "x" + link[1])).as(link[2]).isFalse();
            }
            assertThat(FoojaySource.vendorHost(link[0])).isNotBlank();
        }
        assertThat(vendors).containsExactlyInAnyOrderElementsOf(FoojaySource.PINNED_VENDORS);
    }

    @Test
    void aDownloadOfAVendorJkitHasNoKeyForMustStillBeOnItsServer() throws IOException {
        // its checksum comes from foojay too: a foojay that could also name the file could pick both
        String zulu = "https://cdn.azul.com/zulu/bin/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz";
        assertThat(one("zulu", "21.0.13", info(zulu, "")).url()).hasToString(zulu);
        for (String elsewhere : new String[] {"https://example.invalid/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz",
                "https://cdn.azul.com.example.invalid/zulu/bin/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz",
                "https://cdn.azul.com/zulu/pdf/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz",
                "https://cdn.azul.com/zulu/bin/x/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz",
                "https://cdn.azul.com:8443/zulu/bin/zulu21.46.19-ca-jdk21.0.13-linux_x64.tar.gz"}) {
            assertThatThrownBy(() -> one("zulu", "21.0.13", info(elsewhere, ""))).as(elsewhere)
                    .isInstanceOf(JkitException.class)
                    .hasMessageContaining("not on cdn.azul.com/zulu/bin/; refusing it")
                    .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly(
                            "jkit downloads zulu builds only from there: try again later, or pick another vendor"));
        }
        // on GitHub, the vendor's own repository's releases, not a fork's or an archive of a commit
        String liberica = "https://github.com/bell-sw/Liberica/releases/download/21.0.13%2B11/bellsoft-jdk21.0.13+11-linux-amd64.tar.gz";
        assertThat(one("liberica", "21.0.13+11", info(liberica, "")).url()).hasToString(liberica);
        for (String elsewhere : new String[] {
                "https://github.com/someone/Liberica/releases/download/21.0.13%2B11/bellsoft-jdk21.0.13+11-linux-amd64.tar.gz",
                "https://github.com/bell-sw/Liberica/archive/bellsoft-jdk21.0.13+11-linux-amd64.tar.gz",
                "https://github.com/bell-sw/Liberica/raw/main/bellsoft-jdk21.0.13+11-linux-amd64.tar.gz"}) {
            assertThatThrownBy(() -> one("liberica", "21.0.13+11", info(elsewhere, ""))).as(elsewhere)
                    .hasMessageContaining("not on BellSoft's GitHub releases");
        }
        assertThatThrownBy(() -> one("semeru", "21.0.13", info("https://github.com/ibmruntimes/openj9/releases/download/x/"
                + "ibm-semeru-open-jdk_x64_linux_21.0.13.tar.gz", ""))).hasMessageContaining("refusing it");
        assertThatThrownBy(() -> one("sap_machine", "21.0.13", info("https://github.com/SAP/other/releases/download/x/"
                + "sapmachine-jdk-21.0.13_linux-x64_bin.tar.gz", ""))).hasMessageContaining("refusing it");
        assertThatThrownBy(() -> one("oracle", "21.0.13", info("https://download.oracle.com.example.invalid/java/21/archive/"
                + "jdk-21.0.13_linux-x64_bin.tar.gz", ""))).hasMessageContaining("not on download.oracle.com/java/");
        // gds.oracle.com links name an artifact: only for Oracle's GraalVM builds, and only in that shape
        String gds = "https://gds.oracle.com/api/20220101/artifacts/42B0A0415697484BABDF56B3892CABA6/content";
        assertThat(FoojaySource.onVendorHost("graalvm_community", URI.create(gds), "graalvm.tar.gz")).isTrue();
        assertThat(FoojaySource.onVendorHost("oracle", URI.create(gds), "jdk.tar.gz")).isFalse();
        assertThat(FoojaySource.onVendorHost("graalvm_community", URI.create(gds.replace("/content", "/x")), "x")).isFalse();
        assertThat(FoojaySource.onVendorHost("graalvm_community", URI.create(gds + "?a=b"), "content")).isFalse();
        // a foojay mirror that rehosts them must keep the vendor's links, as for unsigned Temurin builds
        URI mirror = URI.create("https://foojay.corp.example/disco/v3.0");
        assertThatThrownBy(() -> one(mirror, "zulu", "21.0.13", info("https://files.corp.example/jdk/z.tar.gz", "")))
                .hasMessageContaining("foojay.corp.example lists the download of zulu 21.0.13 at https://files.corp.example/jdk/z.tar.gz")
                .satisfies(e -> assertThat(((JkitException) e).hints()).singleElement().asString()
                        .contains("zulu builds, which jkit has no key to check, only from there: your foojay mirror"
                                + " (JKIT_SOURCES_FOOJAY_URL) must keep their links"));
        assertThat(one(mirror, "zulu", "21.0.13", info(zulu, "")).url()).hasToString(zulu);
        // one on this machine serves what it likes
        String builds = "{\"result\":[" + pkg("b", "zulu", 21, "21.0.13", "glibc", "") + "]}";
        assertThat(new FoojaySource(fake(Map.of("jdk_version=", builds, "ids/b", info("http://127.0.0.1:9/b.tar.gz", ""))),
                URI.create("http://127.0.0.1:9/disco/")).resolve(Tool.JDK, "21", Vendor.of("zulu"),
                Platform.LINUX_X64, false, false).url()).hasToString("http://127.0.0.1:9/b.tar.gz");
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
    void noMatchNamesOnlyVendorsThatHaveTheVersion() {
        String index = "{\"result\":[" + String.join(",",
                pkg("t", "temurin", 21, "21.0.5+11", "glibc", ""),
                pkg("z", "zulu", 21, "21.0.5+11", "glibc", ""),
                pkg("c", "corretto", 21, "21.0.5+11", "glibc", "")) + "]}";
        String zulu = "{\"result\":[" + String.join(",",
                pkg("z", "zulu", 21, "21.0.5+11", "glibc", ""), pkg("z3", "zulu", 21, "21.0.3+9", "glibc", "")) + "]}";
        String corretto = "{\"result\":[" + String.join(",",
                pkg("c", "corretto", 21, "21.0.5+11", "glibc", ""), pkg("c4", "corretto", 21, "21.0.4+7", "glibc", "")) + "]}";
        FoojaySource foojay = new FoojaySource(fake(Map.of("latest=available", index,
                "distribution=temurin", "{\"result\":[]}", "distribution=zulu", zulu, "distribution=corretto", corretto)),
                FoojaySource.DEFAULT_BASE);
        // an older build only zulu still lists
        assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "21.0.3", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .hasMessageContaining("temurin has no build matching jdk@21.0.3")
                .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly("available from: zulu"));
        // a version nobody has: no list of vendors that don't have it either
        assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "21.0.99", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .hasMessageContaining("temurin has no build matching jdk@21.0.99")
                .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly(
                        "no other vendor has jdk@21.0.99 either; the newest jdk 21 is 21.0.5+11 (corretto, temurin, zulu)",
                        "see them all: jkit search jdk@21"));
    }

    /** {@link #fake}, offline: what a CachingHttp with --offline does with what it has cached. */
    static Http offline(Map<String, String> cached) {
        Http fake = fake(cached);
        return new Http() {
            @Override
            public String get(URI uri) throws IOException {
                return fake.get(uri);
            }

            @Override
            public void download(URI uri, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean offline() {
                return true;
            }
        };
    }

    @Test
    void noMatchDoesNotClaimNobodyHasItWhenAVendorCouldNotBeAsked() {
        String index = "{\"result\":[" + String.join(",",
                pkg("t", "temurin", 21, "21.0.5+11", "glibc", ""),
                pkg("z", "zulu", 21, "21.0.5+11", "glibc", ""),
                pkg("c", "corretto", 21, "21.0.5+11", "glibc", "")) + "]}";
        String corretto = "{\"result\":[" + pkg("c", "corretto", 21, "21.0.5+11", "glibc", "") + "]}";
        // zulu's list fails (404 here): it may have 21.0.3, so "no other vendor has it" would be a guess
        Map<String, String> routes = Map.of("latest=available", index, "distribution=temurin", "{\"result\":[]}",
                "distribution=corretto", corretto);
        for (Http http : List.of(fake(routes), offline(routes))) {
            FoojaySource foojay = new FoojaySource(http, FoojaySource.DEFAULT_BASE);
            assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "21.0.3", Vendor.of("temurin"), Platform.LINUX_X64,
                    false, false))
                    .hasMessageContaining("temurin has no build matching jdk@21.0.3")
                    .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly(
                            "other vendors with JDK 21: corretto, zulu", "see them all: jkit search jdk@21"));
        }
        // offline, no vendor's list is asked for at all, cached or not
        List<String> asked = new ArrayList<>();
        Http counting = new Http() {
            @Override
            public String get(URI uri) throws IOException {
                asked.add(uri.toString());
                return SourcesTest.offline(routes).get(uri);
            }

            @Override
            public void download(URI uri, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean offline() {
                return true;
            }
        };
        assertThatThrownBy(() -> new FoojaySource(counting, FoojaySource.DEFAULT_BASE)
                .resolve(Tool.JDK, "21.0.3", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class);
        assertThat(asked).noneMatch(u -> u.contains("distribution=corretto") || u.contains("distribution=zulu"));
    }

    @Test
    void offlineAnInstallUsesTheNewestBuildsSearchCached() throws IOException {
        // jkit search jdk@17 caches each vendor's newest builds and the lookup of each; not temurin's 17 list
        String index = "{\"result\":[" + pkg("b", "temurin", 21, "21.0.13+9", "glibc", "") + "]}";
        Map<String, String> cached = Map.of("latest=available", index, "ids/b", INFO);
        FoojaySource foojay = new FoojaySource(offline(cached), FoojaySource.DEFAULT_BASE);
        Artifact a = foojay.resolve(Tool.JDK, "21", Vendor.of("temurin"), Platform.LINUX_X64, false, false);
        assertThat(a.version()).isEqualTo(Version.parse("21.0.13+9"));
        // a version the cached newest builds can't answer: say which command fills the cache
        assertThatThrownBy(() -> foojay.resolve(Tool.JDK, "21.0.5", Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class)
                .hasMessage("--offline, but the list of temurin 21 builds isn't cached")
                .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly(
                        "fill the cache: jkit search jdk@21 temurin (without --offline)"));
        // as online, a build filed under 21 whose version is another major's isn't a 21 build:
        // Temurin 17.0.3 has no signature, which Temurin 21 builds must have
        String misfiled = "{\"result\":[" + pkg("o", "temurin", 21, "17.0.3+7", "glibc", "") + "]}";
        FoojaySource stale = new FoojaySource(offline(Map.of("latest=available", misfiled, "ids/o", info(
                "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.3%2B7/o.tar.gz", ""))),
                FoojaySource.DEFAULT_BASE);
        assertThatThrownBy(() -> stale.resolve(Tool.JDK, null, Vendor.of("temurin"), Platform.LINUX_X64, false, false))
                .isInstanceOf(JkitException.class)
                .hasMessage("--offline, but the list of temurin 21 builds isn't cached");
    }

    @Test
    void aVendorTwoUpdatesBehindTheNewestOfItsMajorIsBehind() {
        List<Pkg> index = List.of(
                new Pkg("o", Vendor.of("openjdk"), 21, Version.parse("21.0.2+13"), "o.tar.gz", "tar.gz", "lts", true),
                new Pkg("t", Vendor.of("temurin"), 21, Version.parse("21.0.9+10"), "t.tar.gz", "tar.gz", "lts", true),
                new Pkg("z", Vendor.of("zulu"), 21, Version.parse("21.0.9+10"), "z.tar.gz", "tar.gz", "lts", true),
                // one update behind: likely just later with it
                new Pkg("l", Vendor.of("liberica"), 21, Version.parse("21.0.8+12"), "l.tar.gz", "tar.gz", "lts", true),
                new Pkg("m", Vendor.of("mandrel"), 21, Version.parse("21+23.1.5"), "m.tar.gz", "tar.gz", "lts", true),
                new Pkg("o25", Vendor.of("openjdk"), 25, Version.parse("25.0.1+8"), "o25.tar.gz", "tar.gz", "lts", true),
                new Pkg("e", Vendor.of("temurin"), 25, Version.parse("25.0.3-ea+1"), "e.tar.gz", "tar.gz", "lts", true));
        Map<Pkg, FoojaySource.Behind> behind = FoojaySource.behind(index);
        assertThat(behind).containsOnlyKeys(index.getFirst());
        assertThat(behind.get(index.getFirst())).isEqualTo(
                new FoojaySource.Behind(Version.parse("21.0.9+10"), List.of("temurin", "zulu")));
    }

    private static Pkg jdk(String vendor, String version) {
        Version v = Version.parse(version);
        return new Pkg(vendor + version, Vendor.of(vendor), v.part(0), v, vendor + ".tar.gz", "tar.gz", "lts", true);
    }

    /** {@link FoojaySource#behind}, as "vendor version → newest (vendors)". */
    private static Map<String, String> behind(List<Pkg> index) {
        Map<String, String> out = new java.util.TreeMap<>();
        FoojaySource.behind(index).forEach((p, b) -> out.put(p.vendor() + " " + p.version(),
                b.newest() + " " + b.vendors()));
        return out;
    }

    @Test
    void theNewestUpdateIsOneTwoVendorsHaveAndNeverAGraalVmReleaseNumber() {
        // Gluon lists its GraalVM releases as 21.1 and 21.2, GraalVM CE its 25.0.x builds as
        // 25.1.3 to 25.4.4.1: those aren't updates of 21 or 25, and the others aren't behind them
        assertThat(behind(List.of(jdk("temurin", "21.0.13+11"), jdk("zulu", "21.0.13"), jdk("gluon_graalvm", "21.2"),
                jdk("gluon_graalvm", "21.1"), jdk("temurin", "25.0.5+11"), jdk("corretto", "25.0.5.11.1"),
                jdk("graalvm_community", "25.4.4.1"), jdk("graalvm_community", "25.1.3"),
                jdk("graalvm_community", "25.0.5")))).isEmpty();
        // an update only one vendor has may be its own; a vendor's older build next to its newest isn't behind
        assertThat(behind(List.of(jdk("temurin", "21.0.15+1"), jdk("zulu", "21.0.13"), jdk("corretto", "21.0.13.11.1"),
                jdk("liberica", "21.0.12+7"), jdk("kona", "21.0.4+1"), jdk("kona", "21.0.13+1"), jdk("openjdk", "21.0.11")))).containsExactly(
                Map.entry("openjdk 21.0.11", "21.0.13.11.1 [corretto, kona, temurin, zulu]"));
        // a GraalVM distribution can be behind, but doesn't set the newest: two of them at 21.0.20
        // don't make the others behind
        assertThat(behind(List.of(jdk("temurin", "21.0.13"), jdk("zulu", "21.0.13"), jdk("graalvm", "21.0.20"),
                jdk("liberica_native", "21.0.20"), jdk("graalvm_community", "21.0.2"))))
                .containsOnlyKeys("graalvm-ce 21.0.2");
        // JDK 8's update numbers move by about 10 a quarter and 1 or 2 a respin: behind is 2 quarters
        assertThat(behind(List.of(jdk("temurin", "8.0.514+1"), jdk("zulu", "8.0.514"), jdk("kona", "8.0.512"),
                jdk("dragonwell", "8.0.502"), jdk("bisheng", "8.0.492")))).containsOnlyKeys("bisheng 8.0.492");
    }

    @Test
    void onFoojaysRealListOnlyVendorsThatStoppedUpdatingAreBehind() throws IOException {
        // each vendor's newest builds for Linux x64, as foojay listed them in November 2026
        FoojaySource foojay = new FoojaySource(fake(Map.of("latest=available", resource("foojay-latest-linux-x64.json"))),
                FoojaySource.DEFAULT_BASE);
        Map<String, String> glibc = behind(foojay.newestPerVendorAndMajor(Platform.LINUX_X64, false));
        assertThat(glibc.keySet()).containsExactlyInAnyOrder(
                "bisheng 8.0.492", "openjdk 8.0.342+7", "semeru 8.0.482.1", "trava 8.0.282+8",
                "jetbrains 11.0.14.1", "liberica-nik 11.0.22+12", "openjdk 11.0.16+8", "sapmachine 11.0.30",
                "trava 11.0.10+6", "trava 11.0.15+1",
                "graalvm 17.0.12", "graalvm-ce 17.0.9", "jetbrains 17.0.14+136722", "openjdk 17.0.2+8",
                // JetBrains moved on to 25; GraalVM CE has no public 21 updates after 21.0.2
                "graalvm-ce 21.0.2", "jetbrains 21.0.11+1163125", "openjdk 21.0.2+13",
                "eliya 25.0.3", "openjdk 25.0.2+10");
        assertThat(glibc.get("openjdk 21.0.2+13")).startsWith("21.0.13");
        assertThat(glibc.get("bisheng 8.0.492")).startsWith("8.0.514");
        Map<String, String> musl = behind(foojay.newestPerVendorAndMajor(Platform.LINUX_X64_MUSL, false));
        assertThat(musl.keySet()).containsExactlyInAnyOrder("kona 8.0.482", "liberica-nik 11.0.22+12",
                "jetbrains 17.0.9", "kona 21.0.4+1");
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
    void foojaysRealVendorListResolvesNamesSynonymsAndJkitsAliases() throws IOException {
        // foojay's distributions answer, names and synonyms as it lists them
        FoojaySource foojay = new FoojaySource(fake(Map.of("distributions", resource("foojay-distributions.json"))),
                FoojaySource.DEFAULT_BASE);
        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (String t : new String[] {"temurin", "Temurin", "adoptium", "ADOPTIUM"}) {
            names.put(t, "temurin");
        }
        names.putAll(Map.of("zulu", "zulu", "Zulu Core", "zulu", "zulucore", "zulu", "corretto", "corretto",
                "microsoft", "microsoft", "Microsoft Build of OpenJDK", "microsoft", "liberica", "liberica",
                "liberica-nik", "liberica_native", "Liberica Native", "liberica_native", "kona", "kona"));
        names.putAll(Map.of("sapmachine", "sap_machine", "SAP Machine", "sap_machine", "sap-machine", "sap_machine",
                "openjdk", "oracle_open_jdk", "Oracle OpenJDK", "oracle_open_jdk", "graalvm-ce", "graalvm_community",
                "GraalVM Community", "graalvm_community", "graalvm", "graalvm", "mandrel", "mandrel", "Red Hat", "redhat"));
        for (Map.Entry<String, String> e : names.entrySet()) {
            assertThat(foojay.requireKnown(Vendor.of(e.getKey()), e.getKey(), "").foojayName()).as(e.getKey())
                    .isEqualTo(e.getValue());
        }
        assertThatThrownBy(() -> foojay.requireKnown(Vendor.of("aoj"), "aoj", "")).hasMessage("unknown JDK vendor 'aoj'");
    }

    @Test
    void foojaysSynonymsCanAddNamesButNeverMoveOne() throws IOException {
        // the vendor decides the signature check and the server pin: a list calling temurin, microsoft
        // and corretto Zulu would install Zulu builds, from anywhere, without a signature
        String poc = "{\"result\":[{\"api_parameter\":\"zulu\",\"synonyms\":[\"temurin\",\"microsoft\",\"corretto\"]}]}";
        String hostile = """
                {"result":[
                  {"api_parameter":"zulu","synonyms":["zulu","Temurin","adoptium","Microsoft","corretto","sapmachine",
                    "liberica","kona","zulucore","shared"]},
                  {"api_parameter":"temurin","synonyms":["temurin"]},
                  {"api_parameter":"liberica","synonyms":["liberica"]},
                  {"api_parameter":"kona","synonyms":["kona","shared"]}]}""";
        for (String list : new String[] {poc, hostile}) {
            FoojaySource foojay = new FoojaySource(fake(Map.of("distributions", list)), FoojaySource.DEFAULT_BASE);
            for (String signing : new String[] {"temurin", "Temurin", "adoptium", "microsoft", "corretto"}) {
                assertThat(foojay.requireKnown(Vendor.of(signing), signing, "").foojayName()).as(signing)
                        .isEqualTo(Vendor.of(signing).foojayName());
            }
            assertThat(foojay.requireKnown(Vendor.of("zulu"), "zulu", "").foojayName()).isEqualTo("zulu");
        }
        FoojaySource foojay = new FoojaySource(fake(Map.of("distributions", hostile)), FoojaySource.DEFAULT_BASE);
        // a distribution's own name wins, and so does a name jkit reads as another vendor
        assertThat(foojay.requireKnown(Vendor.of("liberica"), "liberica", "").foojayName()).isEqualTo("liberica");
        assertThat(foojay.requireKnown(Vendor.of("kona"), "kona", "").foojayName()).isEqualTo("kona");
        assertThatThrownBy(() -> foojay.requireKnown(Vendor.of("sapmachine"), "sapmachine", ""))
                .hasMessage("unknown JDK vendor 'sapmachine'");
        // a synonym two distributions claim means neither; one only Zulu claims still works
        assertThatThrownBy(() -> foojay.requireKnown(Vendor.of("shared"), "shared", "")).hasMessage("unknown JDK vendor 'shared'");
        assertThat(foojay.requireKnown(Vendor.of("ZuluCore"), "ZuluCore", "").foojayName()).isEqualTo("zulu");
        // the vendors jkit has keys for need no list at all
        FoojaySource none = new FoojaySource(fake(Map.of()), FoojaySource.DEFAULT_BASE);
        assertThat(none.requireKnown(Vendor.of("adoptium"), "adoptium", "").foojayName()).isEqualTo("temurin");
        assertThatThrownBy(() -> none.requireKnown(Vendor.of("zulu"), "zulu", "")).isInstanceOf(IOException.class);
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
        routes.put("ids/o9", "{\"result\":[{\"direct_download_uri\":\"https://download.oracle.com/java/17/archive/o9.tar.gz\",\"checksum\":\"\"}]}");
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
        routes.put("ids/o9", "{\"result\":[{\"direct_download_uri\":\"https://download.oracle.com/java/17/archive/o9.tar.gz\",\"checksum\":\"\"}]}");
        FoojaySource foojay = new FoojaySource(fake(routes), FoojaySource.DEFAULT_BASE);
        Pkg newest = foojay.builds(17, Vendor.of("oracle"), Platform.LINUX_X64, false, false).getFirst();
        FoojaySource.Availability a = foojay.availability(newest, "17", Platform.LINUX_X64, false, false);
        assertThat(a.pkg().version().raw()).isEqualTo("17.0.9"); // the build install would pick
        assertThat(a.checksum()).isFalse();

        routes.put("ids/o9", none);
        routes.put("ids/o8", "{\"result\":[{\"direct_download_uri\":\"https://download.oracle.com/java/17/archive/o8.tar.gz\"}]}");
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
