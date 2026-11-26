package dev.jkit.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * Ignores terminal signals while jkit waits for a child that receives them itself, and ends jkit
 * from the signal that ended the child.
 */
final class Signals {
    record Saved(Signal signal, SignalHandler previous) { }

    private Signals() { }

    /**
     * Ignores each signal this platform has; returns what to restore. With a handler that does
     * nothing, not SIG_IGN: a child started meanwhile would inherit SIG_IGN, and Ctrl-C couldn't
     * stop it. One that was ignored already (a background job's SIGINT) stays ignored.
     */
    static List<Saved> ignore(String... names) {
        List<Saved> saved = new ArrayList<>();
        for (String name : names) {
            try {
                Signal signal = new Signal(name);
                SignalHandler previous = Signal.handle(signal, s -> { });
                if (previous == SignalHandler.SIG_IGN) {
                    Signal.handle(signal, SignalHandler.SIG_IGN);
                }
                saved.add(new Saved(signal, previous));
            } catch (IllegalArgumentException | UnsupportedOperationException notHere) {
                // no such signal on this OS (QUIT on Windows), or the VM reserves it
            }
        }
        return saved;
    }

    /** Runs {@code action} on each signal this platform has, instead of exiting; returns what to restore. */
    static List<Saved> handle(Runnable action, String... names) {
        List<Saved> saved = new ArrayList<>();
        for (String name : names) {
            try {
                Signal signal = new Signal(name);
                saved.add(new Saved(signal, Signal.handle(signal, s -> action.run())));
            } catch (IllegalArgumentException | UnsupportedOperationException notHere) {
                // no such signal on this OS, or the VM reserves it
            }
        }
        return saved;
    }

    /**
     * The signal a child's exit code says ended it (128 + its number, on Unix): HUP, INT or TERM;
     * null for any other code, and on Windows, where exit codes don't mean signals.
     */
    static String endedBy(int exitCode, boolean windows) {
        if (windows) {
            return null;
        }
        return switch (exitCode) {
            case 128 + 1 -> "HUP";
            case 128 + 2 -> "INT";
            case 128 + 15 -> "TERM";
            default -> null;
        };
    }

    /**
     * Ends jkit from the signal that ended its child, with the default action (not jkit's own
     * handler, which would only exit with the same code): the caller then sees what it would see
     * without jkit in between. Returns when there is no such signal, or it can't be raised here.
     */
    static void raiseFor(int exitCode) {
        String name = endedBy(exitCode, File.separatorChar == '\\');
        if (name == null) {
            return;
        }
        System.out.flush();
        System.err.flush();
        try {
            Signal.handle(new Signal(name), SignalHandler.SIG_DFL);
            // Signal.raise only raises a signal that has a Java handler: kill(1) sends it to jkit
            // with the default action, which ends it
            String kill = Files.isExecutable(Path.of("/bin/kill")) ? "/bin/kill" : "kill";
            new ProcessBuilder(kill, "-" + name, Long.toString(ProcessHandle.current().pid()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
            Thread.sleep(2000); // delivered by now; if not, jkit exits with the child's code instead
        } catch (IllegalArgumentException | UnsupportedOperationException | IOException notHere) {
            // the VM reserves it, or no kill: jkit exits with the child's code instead
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void restore(List<Saved> saved) {
        for (Saved s : saved) {
            try {
                Signal.handle(s.signal(), s.previous());
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                // nothing to put back
            }
        }
    }
}
