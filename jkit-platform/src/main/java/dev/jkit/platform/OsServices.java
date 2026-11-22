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
    String hook(String shell, Path jkitRoot);

    /**
     * What the hook adds to switch to a project's versions on {@code cd} (shell.auto_switch), or
     * null when that shell doesn't support it yet.
     */
    default String autoSwitchHook(String shell) {
        return Hooks.autoSwitch(shell);
    }

    static OsServices current() {
        return Platform.current().isWindows() ? new WindowsOs() : new UnixOs();
    }
}
