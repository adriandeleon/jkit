package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.jkit.model.Platform;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.FoojaySource.Availability;
import dev.jkit.sources.FoojaySource.Pkg;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTest {

    private static Pkg pkg(String vendor) {
        return new Pkg("id", Vendor.of(vendor), 21, Version.parse("21.0.13"), "jdk.tar.gz", "tar.gz", "lts", true);
    }

    private static SearchCommand.Verdict verdict(Availability a) {
        SearchCommand cmd = new SearchCommand();
        cmd.spec = ToolSpec.parse("jdk@21");
        return cmd.verdict(a);
    }

    private static String foojayPkg(String id, String vendor, String version) {
        return """
                {"id":"%s","distribution":"%s","major_version":%d,"java_version":"%s","lib_c_type":"%s",
                 "archive_type":"tar.gz","filename":"%s.tar.gz","javafx_bundled":false,"term_of_support":"lts",
                 "feature":[]}""".formatted(id, vendor, Version.parse(version).part(0), version, Platform.current().libc(), id);
    }

    /** search against a server on localhost that answers every foojay list with one Temurin 21.0.5. */
    private String search(Path tmp, String spec, String vendor) throws Exception {
        return search(tmp, spec, vendor, false, List.of(foojayPkg("t21", "temurin", "21.0.5+11")), List.of("3.9.11"));
    }

    /** search against a server on localhost that answers every foojay list with {@code pkgs}, Maven with {@code maven}. */
    private String search(Path tmp, String spec, String vendor, boolean json, List<String> pkgs, List<String> maven)
            throws Exception {
        String pkg = String.join(",", pkgs);
        String metadata = "<metadata><versioning><versions>"
                + maven.stream().map(v -> "<version>" + v + "</version>").collect(Collectors.joining())
                + "</versions></versioning></metadata>";
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.endsWith("maven-metadata.xml") ? metadata
                    : path.contains("packages") ? "{\"result\":[" + pkg + "]}"
                    : path.contains("/ids/") ? "{\"result\":[{\"direct_download_uri\":\"https://example.invalid/"
                            + path.substring(path.lastIndexOf('/') + 1) + ".tar.gz\",\"checksum\":\"" + "ab".repeat(32)
                            + "\",\"checksum_type\":\"sha256\"}]}"
                    : path.contains("distributions") ? "{\"result\":[{\"api_parameter\":\"temurin\",\"synonyms\":[\"temurin\"]}]}"
                    : "{\"result\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Map<String, String> env = new HashMap<>(Map.of("JKIT_HOME", tmp.resolve("jkit").toString(),
                    "HOME", tmp.toString(), "JKIT_SOURCES_FOOJAY_URL", base, "JKIT_SOURCES_MAVEN_URL", base));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            SearchCommand cmd = new SearchCommand();
            cmd.spec = ToolSpec.parse(spec);
            cmd.vendorText = vendor;
            cmd.jsonFlag.json = json;
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertThat(cmd.run(Context.forEnvOnline(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8)))).isZero();
            // the version lists it fetched are cached, and it says so on stderr (in the document under --json)
            assertThat(json ? out.toString(StandardCharsets.UTF_8) : err.toString(StandardCharsets.UTF_8))
                    .contains("127.0.0.1");
            return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noMatchSaysSoInsteadOfAnEmptyTable(@TempDir Path tmp) throws Exception {
        assertThat(search(tmp, "jdk@21.0.99", null))
                .isEqualTo("no jdk build matches jdk@21.0.99\njkit search jdk lists the versions there are\n");
        assertThat(search(tmp, "jdk@21.0.99", "temurin"))
                .isEqualTo("no temurin jdk build matches jdk@21.0.99\njkit search jdk@21 temurin lists its 21 builds\n");
        assertThat(search(tmp, "maven@9", null))
                .isEqualTo("no maven version matches maven@9\njkit search maven lists the versions there are\n");
        // a match is still a table
        assertThat(search(tmp, "jdk@21.0", null)).startsWith("temurin *").contains("21.0.5+11");
    }

    @Test
    void whenOnlyPreReleasesMatchTheHintSaysAddPre(@TempDir Path tmp) throws Exception {
        assertThat(search(tmp, "maven@4", null, false, List.of(), List.of("3.9.11", "4.0.0-rc-7")))
                .isEqualTo("no maven version matches maven@4\nonly pre-releases match maven@4: add --pre\n");
    }

    @Test
    void aVendorThatStoppedUpdatingAReleaseIsMarkedBehind(@TempDir Path tmp) throws Exception {
        List<String> pkgs = List.of(foojayPkg("o21", "oracle_open_jdk", "21.0.2+13"),
                foojayPkg("t21", "temurin", "21.0.9+10"));
        String rows = search(tmp.resolve("a"), "jdk@21", null, false, pkgs, List.of());
        assertThat(rows).contains("openjdk          21.0.2+13").contains("↓ behind: newest 21 is 21.0.9+10")
                .contains("↓ behind: the vendor stopped updating this release; other vendors have newer builds");
        assertThat(rows.lines().filter(l -> l.startsWith("temurin")).findFirst().orElseThrow()).doesNotContain("behind");
        String table = search(tmp.resolve("b"), "jdk", null, false, pkgs, List.of());
        assertThat(table).contains("openjdk   ↓").contains("↓ behind (the vendor stopped updating it)");
        JsonNode doc = new ObjectMapper().readTree(search(tmp.resolve("c"), "jdk@21", null, true, pkgs, List.of()));
        JsonNode openjdk = null;
        for (JsonNode a : doc.path("available")) {
            if (a.path("vendor").asText().equals("openjdk")) {
                openjdk = a;
            } else {
                assertThat(a.path("behind").isNull()).isTrue();
            }
        }
        assertThat(openjdk).isNotNull();
        assertThat(openjdk.path("behind").path("newest").asText()).isEqualTo("21.0.9+10");
        assertThat(openjdk.path("behind").path("vendors").get(0).asText()).isEqualTo("temurin");
    }

    @Test
    void searchMarksWhatInstallWouldRefuse() {
        URI sig = URI.create("https://example.invalid/jdk.tar.gz.sig");
        // a checksum: installs
        assertThat(verdict(new Availability(pkg("zulu"), true, null))).isEqualTo(SearchCommand.Verdict.OK);
        // no checksum, but a signature by a key jkit has (Corretto): installs
        assertThat(verdict(new Availability(pkg("corretto"), false, sig))).isEqualTo(SearchCommand.Verdict.OK);
        // no checksum and no signature jkit can check: needs --allow-unverified
        assertThat(verdict(new Availability(pkg("dragonwell"), false, null)))
                .isEqualTo(SearchCommand.Verdict.UNVERIFIED);
        assertThat(verdict(new Availability(pkg("dragonwell"), false, sig)))
                .isEqualTo(SearchCommand.Verdict.UNVERIFIED);
        // Amazon's 2019 builds, on its old CloudFront host: signed with keys it has retired
        URI retired = URI.create("https://d3pxv6yz143wms.cloudfront.net/8.232.09.1/jdk.tar.gz.sig");
        assertThat(verdict(new Availability(pkg("corretto"), false, retired)))
                .isEqualTo(SearchCommand.Verdict.UNVERIFIED);
        // no direct download
        assertThat(verdict(new Availability(null, false, null))).isEqualTo(SearchCommand.Verdict.NO_DOWNLOAD);
    }
}
