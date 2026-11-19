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

    public CachingHttp(Http delegate, Path dir, Duration ttl, Clock clock, boolean refresh, boolean offline) {
        this.delegate = delegate;
        this.dir = dir;
        this.ttl = ttl;
        this.clock = clock;
        this.refresh = refresh;
        this.offline = offline;
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
            throw new IOException("offline and nothing cached for " + uri);
        }
        String body = delegate.get(uri);
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "index-", ".tmp");
        Files.writeString(tmp, body);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return body;
    }

    @Override
    public void download(URI uri, Path target, Progress progress) throws IOException {
        if (offline) {
            throw new IOException("offline: cannot download " + uri);
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
