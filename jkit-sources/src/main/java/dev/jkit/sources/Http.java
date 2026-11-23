package dev.jkit.sources;

import java.io.IOException;
import java.net.URI;
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
     * Downloads and checksums must come over https: a checksum fetched over plain http proves
     * nothing. Loopback hosts are allowed for local mirrors and tests.
     */
    static URI requireSecure(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) {
            throw new dev.jkit.model.JkitException("refusing to download over " + uri.getScheme() + ": " + uri,
                    "jkit only downloads tools and checksums over https");
        }
        return uri;
    }

    @FunctionalInterface
    interface Progress {
        Progress NONE = (done, total) -> { };

        /** {@code total} is -1 when the server sends no length. */
        void update(long done, long total);
    }
}
