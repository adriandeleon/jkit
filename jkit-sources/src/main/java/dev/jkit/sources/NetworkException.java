package dev.jkit.sources;

import java.io.IOException;
import java.net.URI;

/** The request never got an answer: no connection, DNS, TLS or timeout. Keeps the URI for the message. */
public final class NetworkException extends IOException {
    private final URI uri;

    public NetworkException(URI uri, IOException cause) {
        super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
        this.uri = uri;
    }

    public URI uri() { return uri; }
}
