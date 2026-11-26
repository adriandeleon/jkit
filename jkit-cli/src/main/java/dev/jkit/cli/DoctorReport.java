package dev.jkit.cli;

import dev.jkit.cli.DoctorCommand.Finding;
import dev.jkit.cli.DoctorCommand.Level;
import dev.jkit.core.ToolStates;
import dev.jkit.model.Platform;
import dev.jkit.model.ShellQuote;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What a check sees, where it writes its findings, and how it shows paths in them. */
final class DoctorReport {
    static final boolean WINDOWS = Platform.hostIsWindows();

    final Context ctx;
    final Path cwd;
    final boolean network;
    final List<Finding> findings = new ArrayList<>();
    ToolStates.Result states;
    String statesError;
    boolean statesReported;
    String id;

    DoctorReport(Context ctx, Path cwd, boolean network) {
        this.ctx = ctx;
        this.cwd = cwd;
        this.network = network;
    }

    void ok(String message) { add(Level.OK, message, null); }

    void info(String message) { add(Level.INFO, message, null); }

    void warn(String message, String fix) { add(Level.WARN, message, fix); }

    void fail(String message, String fix) { add(Level.FAIL, message, fix); }

    private void add(Level level, String message, String fix) {
        findings.add(new Finding(id, level, message, fix));
    }

    /** The tool states, or null (with a fail line already reported) when they couldn't be read. */
    ToolStates.Result states() {
        if (states == null && statesError != null && !statesReported) {
            statesReported = true;
            fail("can't read which versions are active: " + statesError, null);
        }
        return states;
    }

    /** A path as one shell word in a fix command: ~/... when that is safe, else quoted. */
    String arg(Path path) {
        String shown = tilde(path);
        if (shown.matches("[A-Za-z0-9_./~+:\\\\-]+")) {
            return shown;
        }
        return ShellQuote.quote(WINDOWS ? "pwsh" : "bash", path.toString());
    }

    /** rm -rf, or PowerShell's Remove-Item on Windows. */
    String removeAll(String target) {
        return (WINDOWS ? "Remove-Item -Recurse -Force " : "rm -rf ") + target;
    }

    String tilde(Path path) {
        return path == null ? "" : Changes.tilde(path.toString());
    }

    String tilde(String text) {
        return Changes.tilde(text);
    }
}
