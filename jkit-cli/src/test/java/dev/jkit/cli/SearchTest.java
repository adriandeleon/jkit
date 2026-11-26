package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
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

    /** search against a server on localhost that answers every foojay list with one Temurin 21.0.5. */
    private String search(Path tmp, String spec, String vendor) throws Exception {
        String pkg = """
                {"id":"t21","distribution":"temurin","major_version":21,"java_version":"21.0.5+11","lib_c_type":"%s",
                 "archive_type":"tar.gz","filename":"t21.tar.gz","javafx_bundled":false,"term_of_support":"lts",
                 "feature":[]}""".formatted(Platform.current().libc());
        String metadata = "<metadata><versioning><versions><version>3.9.11</version></versions></versioning></metadata>";
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.endsWith("maven-metadata.xml") ? metadata
                    : path.contains("packages") ? "{\"result\":[" + pkg + "]}"
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
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertThat(cmd.run(Context.forEnvOnline(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8)))).isZero();
            // the version lists it fetched are cached, and it says so on stderr
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("saved from 127.0.0.1");
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
