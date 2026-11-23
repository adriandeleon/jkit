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
            return new JkitException("permission denied: " + a.getFile(), java.io.File.separatorChar == '\\'
                    ? "something may have the file open (an antivirus scan, an IDE, a Gradle daemon): wait a moment, "
                            + "then run this again"
                    : "check the folder's owner, or set JKIT_HOME to a folder you can write");
        }
        if (ex instanceof NoSuchFileException f) {
            return new JkitException("file not found: " + f.getFile());
        }
        if (ex instanceof java.nio.file.DirectoryNotEmptyException || ex instanceof java.nio.file.FileAlreadyExistsException
                || ex instanceof java.nio.file.NotDirectoryException) {
            java.nio.file.FileSystemException f = (java.nio.file.FileSystemException) ex;
            return new JkitException("something else is in the way at " + f.getFile()
                    + (f.getOtherFile() == null ? "" : " (or " + f.getOtherFile() + ")"),
                    "move it aside, then run this again");
        }
        if (ex instanceof java.io.IOException io && !(ex instanceof java.nio.charset.CharacterCodingException)) {
            // disk full, read-only file system, file too large, a folder where a link should be:
            // this machine's trouble, not a jkit bug
            String message = io.getMessage() == null ? io.getClass().getSimpleName() : io.getMessage();
            return new JkitException("file system error: " + message,
                    "check free space and that " + "the jkit home (JKIT_HOME) is writable, then run this again");
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
