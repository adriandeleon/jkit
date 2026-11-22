package dev.jkit.sources;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Caches {@link #get} responses as files for a fixed time (the version index). Downloads are
 * never cached. Offline mode serves any cached copy, however old.
 */
public final class CachingHttp implements Http {
    private final Http delegate;
    private final Path dir;
    private final Duration ttl;
    private final Clock clock;
    private final boolean refresh;
    private final boolean offline;
    private final java.util.function.Consumer<String> warn;

    public CachingHttp(Http delegate, Path dir, Duration ttl, Clock clock, boolean refresh, boolean offline) {
        this(delegate, dir, ttl, clock, refresh, offline, message -> { });
    }

    /** {@code warn} hears when an old cached copy stands in for a request that failed. */
    public CachingHttp(Http delegate, Path dir, Duration ttl, Clock clock, boolean refresh, boolean offline,
                       java.util.function.Consumer<String> warn) {
        this.delegate = delegate;
        this.dir = dir;
        this.ttl = ttl;
        this.clock = clock;
        this.refresh = refresh;
        this.offline = offline;
        this.warn = warn;
    }

    @Override
    public String get(URI uri) throws IOException {
        Path file = dir.resolve(key(uri) + ".cache");
        if (Files.isRegularFile(file)) {
            Instant written = Files.getLastModifiedTime(file).toInstant();
            boolean fresh = written.plus(ttl).isAfter(clock.instant());
            if (offline || (fresh && !refresh)) {
                return Files.readString(file);
            }
        }
        if (offline) {
            throw new dev.jkit.model.JkitException("--offline, but there is no cached version list from "
                    + uri.getHost() + " yet", "run the command once without --offline");
        }
        String body;
        try {
            body = delegate.get(uri);
        } catch (NetworkException e) {
            if (Files.isRegularFile(file)) {
                Duration age = Duration.between(Files.getLastModifiedTime(file).toInstant(), clock.instant());
                warn.accept("couldn't reach " + uri.getHost() + " (" + e.getMessage() + "); using the version list from "
                        + (age.toHours() > 0 ? age.toHours() + " h" : age.toMinutes() + " min") + " ago");
                return Files.readString(file);
            }
            throw e;
        }
        if (looksLikeHtml(body)) {
            // a captive portal or proxy error page: don't keep it for a day
            throw new dev.jkit.model.JkitException("got a web page instead of data from " + uri.getHost()
                    + " (a captive portal or proxy login?)", "sign in to the network in a browser, then try again");
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "index-", ".tmp");
        try {
            Files.writeString(tmp, body);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return body;
    }

    /** The sources answer JSON or XML; HTML means something in between answered instead. */
    static boolean looksLikeHtml(String body) {
        String start = body.stripLeading();
        start = start.substring(0, Math.min(start.length(), 64)).toLowerCase(java.util.Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html");
    }

    @Override
    public void download(URI uri, Path target, Progress progress) throws IOException {
        if (offline) {
            throw new dev.jkit.model.JkitException("--offline, so nothing can be downloaded",
                    "run the command without --offline");
        }
        delegate.download(uri, target, progress);
    }

    private static String key(URI uri) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
