package dev.jkit.sources;

import java.io.IOException;
import java.net.URI;

/** A server answered, but not with 2xx. */
public final class HttpStatusException extends IOException {
    private final int status;
    private final URI uri;

    public HttpStatusException(int status, URI uri) {
        super("HTTP " + status + " from " + uri);
        this.status = status;
        this.uri = uri;
    }

    public int status() { return status; }

    public URI uri() { return uri; }
}
