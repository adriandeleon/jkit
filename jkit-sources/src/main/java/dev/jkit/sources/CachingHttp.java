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
    private final Listener listener;

    /** Hears what the cache does on disk and when it falls back, so commands can say so. */
    public interface Listener {
        Listener NONE = new Listener() { };

        /** A response was written to {@code file}; {@code created} is false when it replaced an older copy. */
        default void stored(URI uri, Path file, boolean created) { }

        /** An old cached copy stands in for a request that failed. */
        default void stale(String message) { }
    }

    public CachingHttp(Http delegate, Path dir, Duration ttl, Clock clock, boolean refresh, boolean offline) {
        this(delegate, dir, ttl, clock, refresh, offline, Listener.NONE);
    }

    public CachingHttp(Http delegate, Path dir, Duration ttl, Clock clock, boolean refresh, boolean offline,
                       Listener listener) {
        this.delegate = delegate;
        this.dir = dir;
        this.ttl = ttl;
        this.clock = clock;
        this.refresh = refresh;
        this.offline = offline;
        this.listener = listener;
    }

    /** The cached copy, however old, when the server is down or unreachable; else {@code failure}. */
    private String staleOr(Path file, URI uri, String why, Exception failure) throws IOException {
        if (Files.isRegularFile(file)) {
            Duration age = Duration.between(Files.getLastModifiedTime(file).toInstant(), clock.instant());
            listener.stale(why + "; using the version list from "
                    + (age.toHours() > 0 ? age.toHours() + " h" : age.toMinutes() + " min") + " ago");
            return Files.readString(file);
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        throw (RuntimeException) failure;
    }

    @Override
    public String getFresh(URI uri) throws IOException {
        if (offline) {
            throw new dev.jkit.model.JkitException("--offline, but " + uri.getHost() + " has to be asked for a checksum",
                    "run the command without --offline");
        }
        return delegate.get(uri);
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
            boolean anyCached;
            try (var files = Files.list(dir)) {
                anyCached = files.anyMatch(f -> f.getFileName().toString().endsWith(".cache"));
            } catch (IOException e) {
                anyCached = false;
            }
            throw new dev.jkit.model.JkitException(anyCached
                    ? "--offline, but this lookup on " + uri.getHost() + " isn't cached (the cache only holds "
                            + "what earlier commands asked for)"
                    : "--offline, but there is no cached version list from " + uri.getHost() + " yet",
                    "run the command once without --offline");
        }
        String body;
        try {
            body = delegate.get(uri);
        } catch (NetworkException e) {
            return staleOr(file, uri, "couldn't reach " + uri.getHost() + " (" + e.getMessage() + ")", e);
        } catch (HttpStatusException e) {
            if (e.status() < 500 && e.status() != 429) {
                throw e; // 404 and friends are answers, not outages
            }
            return staleOr(file, uri, uri.getHost() + " answered " + e.status(), e);
        }
        if (looksLikeHtml(body)) {
            // a captive portal or proxy error page: don't keep it for a day
            return staleOr(file, uri, "got a web page instead of data from " + uri.getHost(),
                    new dev.jkit.model.JkitException("got a web page instead of data from " + uri.getHost()
                            + " (a captive portal or proxy login?)", "sign in to the network in a browser, then try again"));
        }
        Files.createDirectories(dir);
        boolean created = !Files.exists(file);
        Path tmp = Files.createTempFile(dir, "index-", ".tmp");
        try {
            Files.writeString(tmp, body);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        listener.stored(uri, file, created);
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
