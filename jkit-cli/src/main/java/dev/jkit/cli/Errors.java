package dev.jkit.cli;

import dev.jkit.model.JkitException;
import dev.jkit.sources.HttpStatusException;
import dev.jkit.sources.NetworkException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import javax.net.ssl.SSLException;

/** Turns low-level failures into messages that say what happened and what to try. */
final class Errors {
    static final String ISSUES = "https://github.com/adriandeleon/jkit/issues";

    private Errors() { }

    static JkitException describe(Throwable ex) {
        if (ex instanceof JkitException je) {
            return je;
        }
        if (ex instanceof HttpStatusException h) {
            return http(h.uri().getHost(), h.status());
        }
        if (ex instanceof NetworkException n) {
            return network(n.uri().getHost(), n.getCause());
        }
        if (ex instanceof AccessDeniedException a) {
            return new JkitException("permission denied: " + a.getFile(),
                    "check the folder's owner, or set JKIT_HOME to a folder you can write");
        }
        if (ex instanceof NoSuchFileException f) {
            return new JkitException("file not found: " + f.getFile());
        }
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return new JkitException("unexpected error: " + message,
                "run again with JKIT_DEBUG=1 for details, and please report it: " + ISSUES);
    }

    static JkitException http(String host, int status) {
        if (status == 429) {
            return new JkitException(host + " is limiting requests (HTTP 429)", "wait a few minutes and try again");
        }
        if (status >= 500) {
            return new JkitException(host + " is having problems (HTTP " + status + ")",
                    "try again later; cached version lists still work with --offline");
        }
        if (status == 404) {
            return new JkitException(host + " no longer has what jkit asked for (HTTP 404)",
                    "the version list may be out of date: add --refresh");
        }
        return new JkitException(host + " rejected jkit's request (HTTP " + status + ")",
                "check the tool, version and vendor you typed",
                "if they look right, please report it: " + ISSUES);
    }

    static JkitException network(String host, Throwable cause) {
        if (cause instanceof UnknownHostException) {
            return new JkitException("can't find " + host + " (no DNS answer)",
                    "check your internet connection", "cached version lists still work with --offline");
        }
        if (cause instanceof HttpTimeoutException) {
            return new JkitException(host + " took too long to answer", "try again; if it keeps happening, check your proxy (HTTPS_PROXY)");
        }
        if (cause instanceof SSLException) {
            return new JkitException("secure connection to " + host + " failed: " + cause.getMessage(),
                    "a company proxy that inspects HTTPS can cause this; its certificate is not trusted yet");
        }
        if (cause instanceof ConnectException) {
            return new JkitException("can't connect to " + host,
                    "check your internet connection or proxy (HTTPS_PROXY)", "cached version lists still work with --offline");
        }
        return new JkitException("network error talking to " + host + ": " + cause.getMessage(),
                "check your internet connection or proxy (HTTPS_PROXY)");
    }
}
