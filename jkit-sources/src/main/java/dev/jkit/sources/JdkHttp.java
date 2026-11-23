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
                // followed by hand, so every hop is checked before anything is sent to it
                .followRedirects(HttpClient.Redirect.NEVER)
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
            // credentials first, up to the last @: a password may hold / or @ itself
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
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
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
            throw new IllegalArgumentException("proxy " + redact(setting) + ": the port isn't a number from 1 to 65535");
        }

        private static String decode(String text) {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        }

        /** The setting without its password, for messages. */
        static String redact(String setting) {
            // with or without a scheme, and whatever the password holds: everything up to the last @
            int at = setting.lastIndexOf('@');
            if (at < 0) {
                return setting;
            }
            int scheme = setting.indexOf("://");
            int start = scheme >= 0 && scheme < at ? scheme + 3 : 0;
            String credentials = setting.substring(start, at);
            int colon = credentials.indexOf(':');
            String user = colon < 0 ? credentials : credentials.substring(0, colon);
            return setting.substring(0, start) + user + (colon < 0 ? "" : ":***") + setting.substring(at);
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
            // a mirror on this machine is reached directly: plain http to it must not cross a proxy
            boolean local = "http".equalsIgnoreCase(uri.getScheme()) && Http.isSecure(uri);
            return List.of(local || bypasses(uri.getHost()) ? Proxy.NO_PROXY : proxy);
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

    /** Version lists and checksums are small: anything bigger is a broken or hostile server. */
    static final long MAX_TEXT = 8L << 20; // foojay's largest answer is about 0.3 MB
    /** The largest JDK archives are under 500 MB. */
    static final long MAX_DOWNLOAD = 2L << 30;
    /** A text answer must be complete within this, however slowly it trickles in. */
    static final Duration TEXT_DEADLINE = Duration.ofMinutes(2);
    /** A download slower than this over a whole idle window counts as stalled. */
    static final long MIN_BYTES_PER_WINDOW = 16 * 1024;
    /** After the first two minutes, a download averaging less than this is given up on. */
    static final long MIN_AVERAGE_BYTES_PER_SECOND = 32 * 1024;
    /** However fast it keeps trickling, a download ends by then (a 500 MB JDK at 50 KB/s takes under 3 h). */
    static final Duration DOWNLOAD_DEADLINE = Duration.ofHours(3);
    static final Duration AVERAGE_GRACE = Duration.ofMinutes(2);
    private static final int MAX_REDIRECTS = 5;

    @Override
    public String get(URI uri) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fetch(uri, () -> {
            body.reset();
            return body;
        }, Progress.NONE, MAX_TEXT, true);
        return body.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void download(URI uri, Path target, Progress progress) throws IOException {
        fetch(uri, () -> Files.newOutputStream(target), progress, MAX_DOWNLOAD, false);
    }

    @FunctionalInterface
    private interface Sink {
        OutputStream open() throws IOException;
    }

    private void fetch(URI uri, Sink sink, Progress progress, long max, boolean text) throws IOException {
        if (proxyError != null) {
            throw new JkitException(proxyError, "fix HTTPS_PROXY (or JKIT_NETWORK_PROXY), e.g. http://proxy.example.com:3128");
        }
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                fetchOnce(uri, sink, progress, max, text);
                return;
            } catch (HttpStatusException e) {
                if (e.status() < 500 && e.status() != 429) {
                    throw e; // 429: too many requests, worth the same backoff as a 5xx
                }
                last = e;
            } catch (NetworkException e) {
                if (e.gaveUp()) {
                    throw e;
                }
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

    private void fetchOnce(URI start, Sink sink, Progress progress, long max, boolean text) throws IOException {
        URI uri = start;
        for (int hop = 0; ; hop++) {
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
                int status = response.statusCode();
                java.util.Optional<String> location = response.headers().firstValue("location");
                if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                        && location.isPresent()) {
                    URI next;
                    try {
                        next = uri.resolve(location.get().trim());
                    } catch (IllegalArgumentException e) {
                        throw new JkitException(uri.getHost() + " redirected to an invalid address: " + location.get());
                    }
                    // checked before anything is sent: https stays https, and plain http only to this machine
                    boolean downgrade = "https".equalsIgnoreCase(uri.getScheme())
                            && !"https".equalsIgnoreCase(next.getScheme());
                    if (!Http.isSecure(next) || downgrade) {
                        throw new JkitException("refusing a redirect from " + uri + " to " + next,
                                "jkit only uses https (or http to this machine)");
                    }
                    if (hop >= MAX_REDIRECTS) {
                        throw new JkitException("too many redirects from " + start);
                    }
                    uri = next;
                    continue;
                }
                if (status / 100 != 2) {
                    throw new HttpStatusException(status, uri);
                }
                long total = response.headers().firstValueAsLong("content-length").orElse(-1);
                if (total > max) {
                    throw tooBig(uri, max);
                }
                try (OutputStream out = sink.open()) {
                    copyWithIdleTimeout(uri, in, out, total, progress, max, text);
                }
                return;
            }
        }
    }

    private static JkitException tooBig(URI uri, long max) {
        return new JkitException(uri.getHost() + " sent more than " + (max >> 20) + " MB for " + uri.getPath()
                + "; refusing it", "a broken or hostile server or mirror: check JKIT_SOURCES_*_URL and the network");
    }

    /** Writing the download failed on this machine (disk full): not a network error, so not retried. */
    private static final class LocalWriteException extends IOException {
        LocalWriteException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * Copies the body, closing it when no bytes arrive for {@link #idle}, or (downloads) fewer than
     * {@link #MIN_BYTES_PER_WINDOW} arrive in one such window, or (text) the whole answer takes longer
     * than {@link #TEXT_DEADLINE}: a server dripping a byte at a time can't hang jkit.
     */
    private void copyWithIdleTimeout(URI uri, InputStream in, OutputStream out, long total, Progress progress,
                                     long max, boolean text) throws IOException {
        long started = System.nanoTime();
        AtomicLong lastData = new AtomicLong(started);
        AtomicLong received = new AtomicLong();
        AtomicLong windowStart = new AtomicLong(started);
        AtomicLong windowBytes = new AtomicLong();
        java.util.concurrent.atomic.AtomicReference<String> stalled = new java.util.concurrent.atomic.AtomicReference<>();
        // past a deadline or too slow on average: trying again would only multiply the time limit
        java.util.concurrent.atomic.AtomicBoolean final_ = new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jkit-http-watchdog");
            t.setDaemon(true);
            return t;
        });
        long checkMillis = Math.max(100, Math.min(5000, idle.toMillis() / 4));
        watchdog.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            String why = null;
            if (now - lastData.get() > idle.toNanos()) {
                why = "no data for " + idle.toSeconds() + " s";
            } else if (text && now - started > TEXT_DEADLINE.toNanos()) {
                why = "no complete answer within " + TEXT_DEADLINE.toSeconds() + " s";
                final_.set(true);
            } else if (!text && now - started > DOWNLOAD_DEADLINE.toNanos()) {
                why = "not finished after " + DOWNLOAD_DEADLINE.toHours() + " hours";
                final_.set(true);
            } else if (!text && now - started > AVERAGE_GRACE.toNanos()
                    && received.get() < MIN_AVERAGE_BYTES_PER_SECOND * ((now - started) / 1_000_000_000L)) {
                // a server can stay above the per-window minimum and still take months for 2 GB
                why = "under " + MIN_AVERAGE_BYTES_PER_SECOND / 1024 + " KB/s on average";
                final_.set(true);
            } else if (!text && now - windowStart.get() > idle.toNanos()) {
                if (received.get() - windowBytes.get() < MIN_BYTES_PER_WINDOW) {
                    why = "under " + MIN_BYTES_PER_WINDOW / 1024 + " KB in " + idle.toSeconds() + " s";
                } else {
                    windowStart.set(now);
                    windowBytes.set(received.get());
                }
            }
            if (why != null && stalled.compareAndSet(null, why)) {
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
                done += n;
                received.set(done);
                if (done > max) {
                    throw tooBig(uri, max);
                }
                try {
                    out.write(buffer, 0, n);
                } catch (IOException e) {
                    throw new LocalWriteException(e);
                }
                progress.update(done, total);
            }
            if (stalled.get() != null) {
                throw new IOException("closed");
            }
        } catch (LocalWriteException e) {
            throw new IOException("cannot write the download: " + e.getMessage(), e.getCause());
        } catch (IOException e) {
            if (stalled.get() != null) {
                throw new NetworkException(uri, new IOException(stalled.get()), final_.get());
            }
            throw new NetworkException(uri, e);
        } finally {
            watchdog.shutdownNow();
        }
    }
}
