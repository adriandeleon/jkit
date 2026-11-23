package dev.jkit.sources;

import java.io.IOException;
import java.net.URI;

/** The request never got an answer: no connection, DNS, TLS or timeout. Keeps the URI for the message. */
public final class NetworkException extends IOException {
    private final URI uri;
    private final boolean gaveUp;

    public NetworkException(URI uri, IOException cause) {
        this(uri, cause, false);
    }

    /** {@code gaveUp}: past a time limit or too slow on average, so not worth trying again. */
    public NetworkException(URI uri, IOException cause, boolean gaveUp) {
        super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        this.uri = uri;
        this.gaveUp = gaveUp;
    }

    public boolean gaveUp() { return gaveUp; }

    public URI uri() { return uri; }
}
