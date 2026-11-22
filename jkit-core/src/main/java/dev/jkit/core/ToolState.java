package dev.jkit.core;

import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import java.nio.file.Path;

/**
 * Where one tool comes from in a shell: what jkit would pick ({@link #wanted}) and what the
 * shell actually has ({@link #source}). Read by {@link ToolStates#read}; see docs/diagnostics.md.
 *
 * @param shellChoice  {@code jkit use} in this shell (JKIT_SHELL_TOOL), or null
 * @param pin          the project's pin, or null
 * @param pinInstall   the install the pin resolves to, or null when none matches
 * @param global       where the global link points, or null when there is no link
 * @param globalMissing true when the global link points at an install that is gone
 * @param current      the tool's *_HOME in this environment, or null
 * @param active       the install {@code current} is, or null (outside jkit, or not set)
 * @param source       where {@code current} comes from
 * @param wanted       what jkit would pick: shell, then project, then global
 * @param wantedHome   the install {@code wanted} names, or null (none, or a pin with no install)
 */
public record ToolState(Tool tool, Path shellChoice, PinnedTool pin, Installed pinInstall, Path global,
                        boolean globalMissing, Path current, Installed active, Source source,
                        Source wanted, Path wantedHome) {

    public enum Source {
        /** {@code jkit use} in this shell. */
        SHELL,
        /** The project file's pin. */
        PROJECT,
        /** The global link. */
        GLOBAL,
        /** A *_HOME jkit didn't set. */
        OUTSIDE,
        /** A jkit install that nothing chooses any more, e.g. from an old {@code jkit env}. */
        STALE,
        NONE;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** True when the shell has exactly what jkit would pick. */
    public boolean matches() {
        if (source != wanted) {
            return false;
        }
        return wanted != Source.PROJECT || pinInstall != null;
    }

    /** True when there is nothing to say about this tool: not set, not pinned, no link. */
    public boolean isEmpty() {
        return source == Source.NONE && wanted == Source.NONE && global == null && pin == null;
    }
}
