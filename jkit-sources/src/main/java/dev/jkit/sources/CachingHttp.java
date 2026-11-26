package dev.jkit.sources;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Caches {@link #get} responses as files for a fixed time (the version index). Downloads are
 * never cached. A lookup that fails online (the server down, a captive portal) falls back to a
 * cached copy up to {@link #STALE_LIMIT} old. Offline mode serves any cached copy, however old.
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

        /** {@code file} was deleted to keep the cache small. */
        default void removed(Path file) { }

        /** A response couldn't be written to the cache (a full disk, a read-only folder); the lookup still worked. */
        default void notStored(String message) { }
    }

    /**
     * The oldest cached answer that stands in for a lookup that failed (the server down, a captive
     * portal). --offline uses any age: that is asked for.
     */
    static final Duration STALE_LIMIT = Duration.ofDays(7);
    /** Cached answers older than this are deleted when the cache is written to. */
    static final Duration MAX_AGE = Duration.ofDays(30);
    /** The most the cache keeps: the oldest answers go first (real ones are a few KB to 0.3 MB). */
    static final long MAX_BYTES = 32L << 20;

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

    /**
     * The cached copy when the server is down or unreachable, if it is at most {@link #STALE_LIMIT}
     * old; else {@code failure}, or for an older copy an error that says --offline uses it.
     */
    private String staleOr(Path file, URI uri, String why, Exception failure) throws IOException {
        if (Files.isRegularFile(file)) {
            Duration age = Duration.between(Files.getLastModifiedTime(file).toInstant(), clock.instant());
            if (age.compareTo(STALE_LIMIT) > 0) {
                // An old list misses every update since, security fixes included, and a network
                // that keeps jkit from the server would otherwise keep it on that list for good.
                List<String> hints = new ArrayList<>(failure instanceof JkitException je && !je.hints().isEmpty()
                        ? je.hints() : List.of("try again later"));
                hints.add("or use the cached list from " + age.toDays() + " days ago on purpose: add --offline");
                throw new JkitException(why + "; the cached version list is " + age.toDays()
                        + " days old, too old to use in its place", hints.toArray(String[]::new));
            }
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
    public void evict(URI uri) {
        try {
            Files.deleteIfExists(dir.resolve(key(uri) + ".cache"));
        } catch (IOException ignored) {
            // the next refresh replaces it anyway
        }
    }

    @Override
    public boolean offline() {
        return offline;
    }

    @Override
    public String getFresh(URI uri) throws IOException {
        if (offline) {
            throw new JkitException("--offline, but " + uri.getHost() + " has to be asked for a checksum",
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
            throw new JkitException(anyCached
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
                    new JkitException("got a web page instead of data from " + uri.getHost()
                            + " (a captive portal or proxy login?)", "sign in to the network in a browser, then try again"));
        }
        if (store(uri, file, body)) {
            prune(file);
        }
        return body;
    }

    /**
     * Writes {@code body} to {@code file} in one step; returns whether it did. A cache that can't be
     * written (a full disk, a read-only folder, a file another program holds open on Windows)
     * doesn't fail the lookup: the answer is in hand, and only the next lookup can't reuse it.
     */
    private boolean store(URI uri, Path file, String body) {
        Path tmp = null;
        try {
            Perms.mkdirs(dir);
            boolean created = !Files.exists(file);
            tmp = Files.createTempFile(dir, "index-", ".tmp");
            Files.writeString(tmp, body);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            listener.stored(uri, file, created);
            return true;
        } catch (IOException e) {
            // a FileSystemException's message is only the path; its reason says what went wrong
            String why = e instanceof FileSystemException fs && fs.getReason() != null ? fs.getReason()
                    : e instanceof AccessDeniedException ? "permission denied"
                    : e instanceof FileSystemException || e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage();
            listener.notStored("couldn't save the answer from " + uri.getHost() + " in the cache at " + dir
                    + " (" + why + "); the next lookup asks again");
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a leftover .tmp in the cache folder is harmless
                }
            }
        }
    }

    /**
     * Keeps the cache under {@link #MAX_BYTES} and {@link #MAX_AGE}, never deleting {@code keep}:
     * every lookup is a file of its own, so a mirror answering thousands of them would otherwise
     * fill the disk.
     */
    private void prune(Path keep) {
        record Entry(Path file, Instant time, long size) { }
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (f.getFileName().toString().endsWith(".cache") && !f.equals(keep)
                        && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new Entry(f, Files.getLastModifiedTime(f).toInstant(), Files.size(f)));
                }
            }
            long total = Files.size(keep);
            for (Entry e : entries) {
                total += e.size();
            }
            entries.sort(Comparator.comparing(Entry::time));
            Instant old = clock.instant().minus(MAX_AGE);
            for (Entry e : entries) {
                if (total <= MAX_BYTES && e.time().isAfter(old)) {
                    break;
                }
                if (Files.deleteIfExists(e.file())) {
                    listener.removed(e.file());
                }
                total -= e.size();
            }
        } catch (IOException e) {
            // a smaller cache is a nicety: the lookup itself worked
        }
    }

    /** The sources answer JSON or XML; HTML means something in between answered instead. */
    static boolean looksLikeHtml(String body) {
        String start = body.stripLeading();
        start = start.substring(0, Math.min(start.length(), 64)).toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html");
    }

    @Override
    public void download(URI uri, Path target, Progress progress) throws IOException {
        requireOnline();
        delegate.download(uri, target, progress);
    }

    @Override
    public void download(URI uri, Path target, Progress progress, long max) throws IOException {
        requireOnline();
        delegate.download(uri, target, progress, max);
    }

    private void requireOnline() {
        if (offline) {
            throw new JkitException("--offline, so nothing can be downloaded",
                    "run the command without --offline");
        }
    }

    static String key(URI uri) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
