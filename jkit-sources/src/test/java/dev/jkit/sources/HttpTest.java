package dev.jkit.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkit.model.JkitException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpTest {
    @TempDir
    Path tmp;

    @Test
    void proxySettingsParseWithUnderscoresCredentialsAndPorts() {
        JdkHttp.ProxySpec p = JdkHttp.ProxySpec.parse("http://us%40er:p%3Aw@squid_proxy:3128/");
        assertThat(p.address().getHostString()).isEqualTo("squid_proxy");
        assertThat(p.address().getPort()).isEqualTo(3128);
        assertThat(p.user()).isEqualTo("us@er");
        assertThat(p.password()).isEqualTo("p:w");
        assertThat(JdkHttp.ProxySpec.parse("proxy.corp").address().getPort()).isEqualTo(8080);
        assertThat(JdkHttp.ProxySpec.parse("http://[::1]:3128").address().getHostString()).isEqualTo("::1");
        assertThatThrownBy(() -> JdkHttp.ProxySpec.parse("proxy.corp:abc")).hasMessageContaining("the port isn't a number");
        assertThatThrownBy(() -> JdkHttp.ProxySpec.parse("socks5://proxy:1080")).hasMessageContaining("only http proxies");
        assertThat(JdkHttp.ProxySpec.redact("http://me:secret@proxy:3128")).isEqualTo("http://me:***@proxy:3128");
        assertThat(JdkHttp.ProxySpec.redact("me:Secr3t@proxy:x")).isEqualTo("me:***@proxy:x");
        assertThat(JdkHttp.ProxySpec.redact("http://me:a/b@c@proxy:1")).isEqualTo("http://me:***@proxy:1");
        // a / in the password is part of the password, never printed as a "port"
        JdkHttp.ProxySpec slash = JdkHttp.ProxySpec.parse("http://alice:hunter2/topsecret@proxy.example:3128");
        assertThat(slash.password()).isEqualTo("hunter2/topsecret");
        assertThat(slash.address().getPort()).isEqualTo(3128);
    }

    @Test
    void badProxyFailsOnlyWhenTheNetworkIsUsed() {
        JdkHttp http = new JdkHttp(Map.of("HTTPS_PROXY", "proxy.corp:abc")); // must not throw here
        assertThatThrownBy(() -> http.get(URI.create("https://example.invalid/")))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("the port isn't a number");
    }

    @Test
    void noProxyMatchesHostsAndDomains() {
        JdkHttp.NoProxySelector s = new JdkHttp.NoProxySelector(
                InetSocketAddress.createUnresolved("proxy", 3128), "localhost, .corp.example,mirror.example:8443");
        assertThat(s.bypasses("localhost")).isTrue();
        assertThat(s.bypasses("repo.corp.example")).isTrue();
        assertThat(s.bypasses("corp.example")).isTrue();
        assertThat(s.bypasses("mirror.example")).isTrue();
        assertThat(s.bypasses("api.foojay.io")).isFalse();
        assertThat(s.bypasses("notcorp.example")).isFalse();
    }

    @Test
    void aMirrorOnThisMachineNeverGoesThroughTheProxy() {
        JdkHttp.NoProxySelector s = new JdkHttp.NoProxySelector(InetSocketAddress.createUnresolved("proxy", 3128), null);
        assertThat(s.select(URI.create("http://localhost:8080/m/")).getFirst()).isEqualTo(java.net.Proxy.NO_PROXY);
        assertThat(s.select(URI.create("http://127.0.0.1:8080/m/")).getFirst()).isEqualTo(java.net.Proxy.NO_PROXY);
        assertThat(s.select(URI.create("https://repo.maven.apache.org/")).getFirst()).isNotEqualTo(java.net.Proxy.NO_PROXY);
    }

    @Test
    void eachRedirectIsCheckedBeforeItIsFollowed() throws Exception {
        java.util.concurrent.atomic.AtomicInteger elsewhere = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            elsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plain", exchange -> {
            // 127.0.0.2 stands in for any host that isn't this machine: plain http to it is refused
            exchange.getResponseHeaders().add("Location", "http://127.0.0.2:" + other.getAddress().getPort() + "/x");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            exchange.getResponseHeaders().add("Location", "/data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/data", exchange -> {
            byte[] body = "fine".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        other.start();
        try {
            JdkHttp http = new JdkHttp(Map.of(), "jkit/test", Duration.ofSeconds(5), 1);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThatThrownBy(() -> http.get(URI.create(base + "/plain")))
                    .isInstanceOf(dev.jkit.model.JkitException.class).hasMessageContaining("refusing a redirect");
            assertThat(elsewhere.get()).isZero();
            assertThat(http.get(URI.create(base + "/ok"))).isEqualTo("fine");
        } finally {
            server.stop(0);
            other.stop(0);
        }
    }

    @Test
    void stalledBodyTimesOut() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            OutputStream out = exchange.getResponseBody();
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(2_000); // then stall (longer than the 500 ms idle limit)
            } catch (InterruptedException ignored) {
                // server stopping
            }
            exchange.close();
        });
        server.start();
        try {
            JdkHttp http = new JdkHttp(Map.of(), "jkit/test", Duration.ofMillis(500), 1);
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            long start = System.nanoTime();
            assertThatThrownBy(() -> http.get(uri)).isInstanceOf(NetworkException.class).hasMessageContaining("no data");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorsAreRetried() throws Exception {
        List<Integer> statuses = new ArrayList<>(List.of(503, 200));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = statuses.removeFirst();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JdkHttp http = new JdkHttp(Map.of(), "jkit/test", Duration.ofSeconds(5), 2);
            assertThat(http.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))).isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }

    private static Http answering(String body, boolean fail) {
        return new Http() {
            @Override
            public String get(URI uri) throws IOException {
                if (fail) {
                    throw new NetworkException(uri, new IOException("connection reset"));
                }
                return body;
            }

            @Override
            public void download(URI uri, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void cacheRefusesHtmlAndFallsBackToAnOldCopyWhenOffline() throws Exception {
        URI uri = URI.create("https://api.foojay.io/x");
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Path dir = tmp.resolve("index");

        CachingHttp portal = new CachingHttp(answering("<!DOCTYPE html><html>log in</html>", false), dir,
                Duration.ofHours(24), clock, false, false);
        assertThatThrownBy(() -> portal.get(uri)).isInstanceOf(JkitException.class).hasMessageContaining("web page");
        assertThat(dir).satisfiesAnyOf(d -> assertThat(d).doesNotExist(), d -> assertThat(d).isEmptyDirectory());

        List<Path> stored = new ArrayList<>();
        new CachingHttp(answering("{\"result\":[]}", false), dir, Duration.ofHours(24), clock, false, false,
                new CachingHttp.Listener() {
                    @Override
                    public void stored(URI u, Path file, boolean created) {
                        assertThat(created).isTrue();
                        stored.add(file);
                    }
                }).get(uri);
        assertThat(stored).hasSize(1); // the cache says what it wrote
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                assertThat(f.getFileName().toString()).endsWith(".cache"); // no leftover temp files
                Files.setLastModifiedTime(f, FileTime.from(now.minus(Duration.ofHours(30))));
            }
        }
        List<String> warnings = new ArrayList<>();
        CachingHttp down = new CachingHttp(answering(null, true), dir, Duration.ofHours(24), clock, false, false,
                new CachingHttp.Listener() {
                    @Override
                    public void stale(String message) {
                        warnings.add(message);
                    }
                });
        assertThat(down.get(uri)).isEqualTo("{\"result\":[]}");
        assertThat(warnings).singleElement().asString().contains("30 h ago");

        // a server outage and a captive portal fall back to the old copy too; a 404 doesn't
        Http outage = new Http() {
            @Override
            public String get(URI u) throws IOException {
                throw new HttpStatusException(503, u);
            }

            @Override
            public void download(URI u, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }
        };
        assertThat(new CachingHttp(outage, dir, Duration.ofHours(24), clock, false, false).get(uri))
                .isEqualTo("{\"result\":[]}");
        assertThat(new CachingHttp(answering("<html>log in</html>", false), dir, Duration.ofHours(24), clock,
                false, false).get(uri)).isEqualTo("{\"result\":[]}");
        Http missing = new Http() {
            @Override
            public String get(URI u) throws IOException {
                throw new HttpStatusException(404, u);
            }

            @Override
            public void download(URI u, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }
        };
        assertThatThrownBy(() -> new CachingHttp(missing, dir, Duration.ofHours(24), clock, false, false).get(uri))
                .isInstanceOf(HttpStatusException.class);
    }

    @Test
    void downloadsMustUseHttps() {
        assertThatThrownBy(() -> Http.requireSecure(URI.create("http://example.com/jdk.tar.gz")))
                .isInstanceOf(JkitException.class);
        assertThat(Http.requireSecure(URI.create("http://127.0.0.1:8080/jdk.tar.gz"))).isNotNull();
        assertThat(Http.requireSecure(URI.create("https://example.com/jdk.tar.gz"))).isNotNull();
    }
}
