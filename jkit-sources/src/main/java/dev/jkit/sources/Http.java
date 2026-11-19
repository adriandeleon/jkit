package dev.jkit.sources;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** The only way jkit talks to the network, so tests can replace it. */
public interface Http {

    String get(URI uri) throws IOException;

    void download(URI uri, Path target, Progress progress) throws IOException;

    @FunctionalInterface
    interface Progress {
        Progress NONE = (done, total) -> { };

        /** {@code total} is -1 when the server sends no length. */
        void update(long done, long total);
    }
}
