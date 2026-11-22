package dev.jkit.sources;

import dev.jkit.model.JkitException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Http} on the JDK's HttpClient. HttpClient ignores HTTPS_PROXY and NO_PROXY, so the proxy
 * is read from the environment here. A response that stops sending data for {@link #idle} fails
 * (HttpClient's own timeout ends when the headers arrive). Requests that fail on the network or
 * with a 5xx are tried again (three tries by default).
 */
public final class JdkHttp implements Http {
    private final int attempts;
    private final String userAgent;
    private final Duration idle;
    private final HttpClient client;
    /** Why the proxy setting can't be used; reported on the first request, so offline commands still work. */
    private final String proxyError;

    public JdkHttp(Map<String, String> env) {
        this(env, "jkit", Duration.ofSeconds(60));
    }

    public JdkHttp(Map<String, String> env, String userAgent, Duration idle) {
        this(env, userAgent, idle, 3);
    }

    /**
     * @param idle     how long a request may wait for its headers or for the next bytes of the body
     * @param attempts tries per request when the network fails or the server answers 5xx
     */
    public JdkHttp(Map<String, String> env, String userAgent, Duration idle, int attempts) {
        this.attempts = Math.max(1, attempts);
        this.userAgent = userAgent;
        this.idle = idle;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        String setting = firstNonBlank(env, "JKIT_NETWORK_PROXY", "HTTPS_PROXY", "https_proxy");
        String error = null;
        if (setting != null) {
            try {
                ProxySpec proxy = ProxySpec.parse(setting);
                builder.proxy(new NoProxySelector(proxy.address(),
                        firstNonBlank(env, "NO_PROXY", "no_proxy")));
                if (proxy.user() != null) {
                    // HttpClient leaves out Basic for proxies of https requests unless this is cleared
                    System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                    builder.authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return getRequestorType() == RequestorType.PROXY
                                    ? new PasswordAuthentication(proxy.user(), proxy.password().toCharArray())
                                    : null;
                        }
                    });
                }
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
        }
        this.proxyError = error;
        this.client = builder.build();
    }

    private static String firstNonBlank(Map<String, String> env, String... names) {
        for (String name : names) {
            String v = env.get(name);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** host:port and optional credentials from a proxy setting such as {@code http://user:pw@squid_proxy:3128}. */
    record ProxySpec(InetSocketAddress address, String user, String password) {
        static ProxySpec parse(String setting) {
            String rest = setting;
            int scheme = rest.indexOf("://");
            if (scheme >= 0) {
                String name = rest.substring(0, scheme).toLowerCase(Locale.ROOT);
                if (!name.equals("http") && !name.equals("https")) {
                    throw new IllegalArgumentException("proxy " + redact(setting) + ": only http proxies are supported, not "
                            + name);
                }
                rest = rest.substring(scheme + 3);
            }
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            String user = null;
            String password = null;
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                String credentials = rest.substring(0, at);
                rest = rest.substring(at + 1);
                int colon = credentials.indexOf(':');
                user = decode(colon < 0 ? credentials : credentials.substring(0, colon));
                password = colon < 0 ? "" : decode(credentials.substring(colon + 1));
            }
            String host = rest;
            int port = 8080;
            if (rest.startsWith("[")) { // [::1]:3128
                int close = rest.indexOf(']');
                if (close < 0) {
                    throw new IllegalArgumentException("proxy " + redact(setting) + ": unclosed [ in the host");
                }
                host = rest.substring(1, close);
                String after = rest.substring(close + 1);
                if (after.startsWith(":")) {
                    port = port(after.substring(1), setting);
                }
            } else {
                int colon = rest.lastIndexOf(':');
                if (colon >= 0) {
                    host = rest.substring(0, colon);
                    port = port(rest.substring(colon + 1), setting);
                }
            }
            if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("proxy " + redact(setting) + ": no usable host name");
            }
            return new ProxySpec(InetSocketAddress.createUnresolved(host, port), user, password);
        }

        private static int port(String text, String setting) {
            try {
                int port = Integer.parseInt(text);
                if (port > 0 && port < 65536) {
                    return port;
                }
            } catch (NumberFormatException e) {
                // below
            }
            throw new IllegalArgumentException("proxy " + redact(setting) + ": '" + text + "' is not a port");
        }

        private static String decode(String text) {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        }

        /** The setting without its password, for messages. */
        static String redact(String setting) {
            return setting.replaceFirst("://([^:@/]*):[^@/]*@", "://$1:***@");
        }
    }

    /** The proxy for every host except those NO_PROXY names (exact, domain suffix, or *). */
    static final class NoProxySelector extends ProxySelector {
        private final Proxy proxy;
        private final List<String> bypass = new ArrayList<>();

        NoProxySelector(InetSocketAddress address, String noProxy) {
            this.proxy = new Proxy(Proxy.Type.HTTP, address);
            if (noProxy != null) {
                for (String entry : noProxy.split("[,\\s]+")) {
                    String e = entry.trim().toLowerCase(Locale.ROOT);
                    int colon = e.lastIndexOf(':');
                    if (colon > 0 && !e.contains("]") && e.indexOf(':') == colon) {
                        e = e.substring(0, colon); // host:port → host
                    }
                    if (!e.isEmpty()) {
                        bypass.add(e.startsWith("*.") ? e.substring(1) : e);
                    }
                }
            }
        }

        boolean bypasses(String host) {
            String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
            for (String b : bypass) {
                if (b.equals("*") || h.equals(b) || h.equals(b.startsWith(".") ? b.substring(1) : b)
                        || h.endsWith(b.startsWith(".") ? b : "." + b)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(bypasses(uri.getHost()) ? Proxy.NO_PROXY : proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // nothing to remember
        }
    }

    private HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("User-Agent", userAgent)
                .timeout(idle)
                .GET()
                .build();
    }

    @Override
    public String get(URI uri) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fetch(uri, () -> {
            body.reset();
            return body;
        }, Progress.NONE);
        return body.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void download(URI uri, Path target, Progress progress) throws IOException {
        fetch(uri, () -> Files.newOutputStream(target), progress);
    }

    @FunctionalInterface
    private interface Sink {
        OutputStream open() throws IOException;
    }

    private void fetch(URI uri, Sink sink, Progress progress) throws IOException {
        if (proxyError != null) {
            throw new JkitException(proxyError, "fix HTTPS_PROXY (or JKIT_NETWORK_PROXY), e.g. http://proxy.example.com:3128");
        }
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                fetchOnce(uri, sink, progress);
                return;
            } catch (HttpStatusException e) {
                if (e.status() < 500 && e.status() != 429) {
                    throw e; // 429: too many requests, worth the same backoff as a 5xx
                }
                last = e;
            } catch (NetworkException e) {
                last = e;
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        }
        throw last;
    }

    private void fetchOnce(URI uri, Sink sink, Progress progress) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new NetworkException(uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new HttpStatusException(response.statusCode(), uri);
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1);
            try (OutputStream out = sink.open()) {
                copyWithIdleTimeout(uri, in, out, total, progress);
            }
        }
    }

    /** Copies the body, closing it when no bytes arrive for {@link #idle}, which ends the blocked read. */
    private void copyWithIdleTimeout(URI uri, InputStream in, OutputStream out, long total, Progress progress)
            throws IOException {
        AtomicLong lastData = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jkit-http-watchdog");
            t.setDaemon(true);
            return t;
        });
        long checkMillis = Math.max(100, Math.min(5000, idle.toMillis() / 4));
        watchdog.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastData.get() > idle.toNanos() && stalled.compareAndSet(false, true)) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // the read fails either way
                }
            }
        }, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
        try {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                lastData.set(System.nanoTime());
                if (n == 0) {
                    continue;
                }
                out.write(buffer, 0, n);
                done += n;
                progress.update(done, total);
            }
            if (stalled.get()) {
                throw new IOException("closed");
            }
        } catch (IOException e) {
            if (stalled.get()) {
                throw new NetworkException(uri, new IOException("no data for " + idle.toSeconds() + " s"));
            }
            throw new NetworkException(uri, e);
        } finally {
            watchdog.shutdownNow();
        }
    }
}
