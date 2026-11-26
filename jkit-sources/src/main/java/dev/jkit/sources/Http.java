package dev.jkit.sources;

import dev.jkit.model.JkitException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** The only way jkit talks to the network, so tests can replace it. */
public interface Http {

    String get(URI uri) throws IOException;

    /** Like {@link #get}, never from or into a cache: for checksum files, read once per install. */
    default String getFresh(URI uri) throws IOException {
        return get(uri);
    }

    void download(URI uri, Path target, Progress progress) throws IOException;

    /**
     * Like {@link #download}, for a small file (a signature): more than {@code max} bytes fails, and
     * the download stops there rather than at the 2 GB a JDK may take.
     */
    default void download(URI uri, Path target, Progress progress, long max) throws IOException {
        download(uri, target, progress);
        if (Files.size(target) > max) {
            throw tooBig(uri, max);
        }
    }

    /** More than {@code max} bytes came for {@code uri}. */
    static JkitException tooBig(URI uri, long max) {
        String limit = max >= 1 << 20 ? (max >> 20) + " MB" : max >= 1 << 10 ? (max >> 10) + " KB" : max + " bytes";
        return new JkitException(uri.getHost() + " sent more than " + limit + " for " + uri.getPath()
                + "; refusing it", "a broken or hostile server or mirror: check JKIT_SOURCES_*_URL and the network");
    }

    /**
     * Whether this client may not use the network. Sources then leave a separate checksum file
     * unfetched (the artifact has none): nothing is downloaded offline, so nothing needs checking,
     * and install, upgrade and sync can still tell what is installed or out of date.
     */
    default boolean offline() {
        return false;
    }

    /** Forgets a cached answer that turned out to be unusable. */
    default void evict(URI uri) {
    }

    /** An address a source sent: a JkitException, not a crash, when it isn't a usable https URL. */
    static URI sent(String text, String what, URI source) {
        URI uri;
        try {
            uri = URI.create(text.strip());
        } catch (IllegalArgumentException e) {
            throw new JkitException("the source sent an invalid address for " + what);
        }
        // plain http to this machine only from a mirror on this machine: a remote source must not
        // send jkit to local services
        if (!"https".equalsIgnoreCase(uri.getScheme()) && "https".equalsIgnoreCase(source.getScheme())) {
            throw new JkitException("refusing " + what + " over " + uri.getScheme() + " from "
                    + source.getHost() + ": " + uri, "jkit only downloads tools and checksums over https");
        }
        return requireSecure(uri);
    }

    /**
     * Downloads and checksums must come over https: a checksum fetched over plain http proves
     * nothing. Loopback hosts are allowed for local mirrors and tests.
     */
    static URI requireSecure(URI uri) {
        if (!isSecure(uri)) {
            throw new JkitException("refusing to download over " + uri.getScheme() + ": " + uri,
                    "jkit only downloads tools and checksums over https");
        }
        return uri;
    }

    /** https, or plain http to this machine (a local mirror, tests). */
    static boolean isSecure(URI uri) {
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return false;
        }
        String host = uri.getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        return "https".equalsIgnoreCase(uri.getScheme()) || loopback && "http".equalsIgnoreCase(uri.getScheme());
    }

    @FunctionalInterface
    interface Progress {
        Progress NONE = (done, total) -> { };

        /** {@code total} is -1 when the server sends no length. */
        void update(long done, long total);

        /** The download starts again, on a new connection, because of {@code why}. */
        default void restarting(String why) { }

        /**
         * Called once, just before the download starts, by the code that starts it. An install
         * calls it only after the checks that need no download have passed, so whatever it prints
         * ("downloading ...") never precedes a refusal.
         */
        default void starting() { }
    }
}
