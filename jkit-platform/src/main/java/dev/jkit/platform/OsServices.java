package dev.jkit.platform;

import dev.jkit.model.Platform;
import java.io.IOException;
import java.nio.file.Path;

/** Everything that differs per operating system. */
public interface OsServices {

    /** Points {@code link} at {@code target}, replacing any existing link. */
    void pointLink(Path link, Path target) throws IOException;

    /** Where {@code link} points, or null when it does not exist. */
    Path readLink(Path link) throws IOException;

    void removeLink(Path link) throws IOException;

    /** The script a shell's rc file evaluates: global PATH entries plus the jkit wrapper function. */
    default String hook(String shell, Path jkitRoot) {
        return hook(shell, jkitRoot, null);
    }

    /** Like {@link #hook(String, Path)}, calling jkit by {@code self}, its absolute path (null: by name). */
    String hook(String shell, Path jkitRoot, String self);

    /**
     * What the hook adds to switch to a project's versions on {@code cd} (shell.auto_switch), or
     * null when that shell doesn't support it yet.
     */
    default String autoSwitchHook(String shell) {
        return autoSwitchHook(shell, null);
    }

    default String autoSwitchHook(String shell, String self) {
        return Hooks.autoSwitch(shell, self);
    }

    /**
     * The user's own environment variables, which programs started outside a shell (an IDE, a
     * Start-menu shortcut) get; null where there is no such thing, and the shell's startup file
     * is the place (macOS, Linux).
     */
    default UserEnv userEnv() {
        return null;
    }

    static OsServices current() {
        return Platform.current().isWindows() ? new WindowsOs() : new UnixOs();
    }
}
