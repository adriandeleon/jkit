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
    void proxyCredentialsArePercentDecodedOnly() {
        // a + is a plus in a URL's user info, not a space as in a form
        JdkHttp.ProxySpec plus = JdkHttp.ProxySpec.parse("http://a+b:p+w%2B%C3%A9@proxy:3128");
        assertThat(plus.user()).isEqualTo("a+b");
        assertThat(plus.password()).isEqualTo("p+w+é");
        assertThat(JdkHttp.ProxySpec.parse("http://me:pässwörd@proxy:3128").password()).isEqualTo("pässwörd");
        assertThatThrownBy(() -> JdkHttp.ProxySpec.parse("http://me:100%@proxy:3128"))
                .hasMessageContaining("must start an escape such as %40").hasMessageNotContaining("100%");
        assertThatThrownBy(() -> JdkHttp.ProxySpec.parse("http://me:a%zzb@proxy:3128"))
                .hasMessageContaining("http://me:***@proxy:3128");
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

    /** Serves {@code size} bytes: slowly (a few KB per 100 ms) to the first {@code slowRequests} requests. */
    private static HttpServer sizedServer(int size, int slowRequests, java.util.concurrent.atomic.AtomicInteger calls)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            boolean slow = calls.incrementAndGet() <= slowRequests;
            byte[] body = new byte[size];
            java.util.Arrays.fill(body, (byte) 'x');
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                if (!slow) {
                    out.write(body);
                    return;
                }
                for (int i = 0; i < size; i += 4096) {
                    out.write(body, i, Math.min(4096, size - i));
                    out.flush();
                    Thread.sleep(100); // 40 KB/s: well above the stall limits, far too slow for the size
                }
            } catch (InterruptedException | IOException ignored) {
                // jkit closed the connection, or the server is stopping
            }
        });
        server.start();
        return server;
    }

    @Test
    void aFarTooSlowDownloadStartsAgainOnceOnANewConnection() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = sizedServer(8 << 20, 1, calls);
        try {
            // 8 MB at 40 KB/s: minutes
            JdkHttp http = new JdkHttp(Map.of(), "jkit/test", Duration.ofSeconds(5), 1, Duration.ofSeconds(1),
                    Duration.ofSeconds(5));
            List<String> restarts = new ArrayList<>();
            Path target = tmp.resolve("big");
            http.download(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/big"), target,
                    new Http.Progress() {
                        @Override
                        public void update(long done, long total) { }

                        @Override
                        public void restarting(String why) {
                            restarts.add(why);
                        }
                    });
            assertThat(restarts).singleElement().asString().startsWith("slow download (").contains("KB/s");
            assertThat(calls.get()).isEqualTo(2);
            assertThat(Files.size(target)).isEqualTo(8 << 20); // the restart wrote the file from scratch
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDownloadThatIsSlowButWillFinishSoonIsLeftAlone() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = sizedServer(80 << 10, 1, calls); // 80 KB at 40 KB/s: two seconds
        try {
            JdkHttp http = new JdkHttp(Map.of(), "jkit/test", Duration.ofSeconds(5), 1, Duration.ofSeconds(1),
                    Duration.ofSeconds(30));
            List<String> restarts = new ArrayList<>();
            Path target = tmp.resolve("small");
            http.download(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/small"), target,
                    new Http.Progress() {
                        @Override
                        public void update(long done, long total) { }

                        @Override
                        public void restarting(String why) {
                            restarts.add(why);
                        }
                    });
            assertThat(restarts).isEmpty();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(Files.size(target)).isEqualTo(80 << 10);
        } finally {
            server.stop(0);
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
    void aServerThatNeverAnswersIsNotAskedAgain() throws Exception {
        // header waits per redirect, times three tries, once added up to about 18 minutes
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(3_000); // no headers within the 500 ms limit
            } catch (InterruptedException ignored) {
                // server stopping
            }
            exchange.close();
        });
        server.start();
        try {
            JdkHttp http = new JdkHttp(Map.of(), "jkit/test", Duration.ofMillis(500), 3);
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            long start = System.nanoTime();
            assertThatThrownBy(() -> http.get(uri)).isInstanceOf(NetworkException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(calls.get()).isEqualTo(1);
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
    void theCacheDropsItsOldestAnswersAndSaysSo() throws Exception {
        // every lookup is a file: a mirror answering thousands of them must not fill the disk
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        Path dir = Files.createDirectories(tmp.resolve("index"));
        Path ancient = Files.writeString(dir.resolve("aaa.cache"), "{}");
        Files.setLastModifiedTime(ancient, FileTime.from(now.minus(Duration.ofDays(40))));
        Path big = Files.write(dir.resolve("bbb.cache"), new byte[(int) CachingHttp.MAX_BYTES]);
        Files.setLastModifiedTime(big, FileTime.from(now.minus(Duration.ofDays(1))));
        Path recent = Files.writeString(dir.resolve("ccc.cache"), "{}");
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofHours(1))));
        List<Path> removed = new ArrayList<>();
        new CachingHttp(answering("{\"result\":[]}", false), dir, Duration.ofHours(24), Clock.fixed(now, ZoneOffset.UTC),
                false, false, new CachingHttp.Listener() {
                    @Override
                    public void removed(Path file) {
                        removed.add(file);
                    }
                }).get(URI.create("https://api.foojay.io/y"));
        assertThat(removed).containsExactly(ancient, big); // oldest first, until under the limit
        assertThat(recent).exists();
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
