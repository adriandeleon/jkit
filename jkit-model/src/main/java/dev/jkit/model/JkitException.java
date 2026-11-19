package dev.jkit.model;

import java.util.List;

/** An error shown to the user as "error: message" plus hint lines, with a specific exit code. */
public class JkitException extends RuntimeException {
    public static final int GENERAL = 1;
    public static final int NOT_INSTALLED = 3;

    private final int exitCode;
    private final List<String> hints;

    public JkitException(String message, String... hints) {
        this(GENERAL, message, hints);
    }

    public JkitException(int exitCode, String message, String... hints) {
        super(message);
        this.exitCode = exitCode;
        this.hints = List.of(hints);
    }

    public int exitCode() { return exitCode; }

    public List<String> hints() { return hints; }
}
