package dev.jkit.cli;

import dev.jkit.model.JkitException;
import java.util.List;

/**
 * A command failed after it had already changed something: the error, plus what changed before it
 * (the {@code changes} list of docs/json.md), so the error document still accounts for it.
 */
final class PartialFailure extends JkitException {
    private final transient List<Object> changes;

    PartialFailure(JkitException cause, List<Object> changes) {
        super(cause.exitCode(), cause.getMessage(), cause.hints().toArray(String[]::new));
        initCause(cause);
        this.changes = List.copyOf(changes);
    }

    List<Object> changes() { return changes; }
}
