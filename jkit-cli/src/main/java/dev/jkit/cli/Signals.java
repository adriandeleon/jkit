package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.WorkDirs;
import dev.jkit.model.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * Ignores terminal signals while jkit waits for a child that receives them itself, and ends jkit
 * from SIGINT after a Ctrl-C, so a shell stops as it would without jkit in between.
 */
final class Signals {
    record Saved(Signal signal, SignalHandler previous) { }

    private static volatile boolean gotInt;

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
                SignalHandler previous = Signal.handle(signal, s -> {
                    if (s.getName().equals("INT")) {
                        gotInt = true; // a Ctrl-C, if the command then dies from SIGINT too
                    }
                });
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
     * Once a signal handler of jkit's runs ({@link WorkDirs#interrupted}), waits for it to end jkit
     * instead of returning: the main thread would otherwise print an error the handler's deletion
     * caused ("file not found" in the folder it was unpacking into) and exit 1 before the handler
     * ends jkit from SIGINT, and a shell running jkit wouldn't stop. Returns at once otherwise, and
     * after half a minute in any case.
     */
    static void awaitHandler() {
        if (!WorkDirs.interrupted()) {
            return;
        }
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether jkit itself got a SIGINT while it ignored it (Ctrl-C reaches the whole process group). */
    static boolean gotInt() {
        return gotInt;
    }

    /**
     * Whether a command's exit code should end jkit with SIGINT: only when the command died from
     * SIGINT and jkit got one too, so a Ctrl-C. A command that merely exits 130, or was sent
     * SIGINT on its own, gives its exit code as it is.
     */
    static boolean ctrlC(int exitCode, boolean jkitGotInt, boolean windows) {
        return jkitGotInt && "INT".equals(endedBy(exitCode, windows));
    }

    /**
     * Whether jkit can end itself with SIGINT here: not on Windows, and not as PID 1 (a container's
     * init), where the kernel drops a signal at its default action that the process sends itself.
     */
    static boolean canRaise(long pid, boolean windows) {
        return !windows && pid != 1;
    }

    /**
     * Ends jkit from SIGINT, with the default action (not jkit's own handler, which would only
     * exit 130), after a Ctrl-C: an interactive bash, or a bash script running jkit, stops on
     * Ctrl-C only when its command died from SIGINT, not when it exited 130. A shell shows 130
     * either way. First deletes the folders an install was working in and the hook's
     * $JKIT_ENV_FILE, for a hook from an older jkit, whose jkit function an interactive bash leaves
     * on Ctrl-C before its rm. Returns when jkit can't end from the signal here; the caller then
     * exits 130.
     */
    static void raiseInt(Map<String, String> env) {
        WorkDirs.deleteAll();
        Path envFile = hookEnvFile(env);
        if (envFile != null) {
            try {
                Files.deleteIfExists(envFile);
            } catch (IOException e) {
                // the system clears its temp folder in time
            }
        }
        if (!canRaise(ProcessHandle.current().pid(), Platform.hostIsWindows())) {
            return;
        }
        try {
            // the default action before the flush: a flush that blocks (a pager that stopped
            // reading) must not keep jkit from ending
            Signal.handle(new Signal("INT"), SignalHandler.SIG_DFL);
            Thread flush = Thread.ofPlatform().daemon().start(() -> {
                System.out.flush();
                System.err.flush();
            });
            flush.join(100);
            // Signal.raise only raises a signal that has a Java handler: kill(1) sends it to jkit
            // with the default action, which ends it
            String kill = Files.isExecutable(Path.of("/bin/kill")) ? "/bin/kill" : "kill";
            new ProcessBuilder(kill, "-INT", Long.toString(ProcessHandle.current().pid()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
            for (int i = 0; i < 50; i++) {
                Thread.sleep(10); // delivered by now, almost always; if not, the caller exits 130
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | IOException notHere) {
            // the VM reserves it, or no kill: the caller exits 130
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The file the hook's jkit function made for this run, from $JKIT_ENV_FILE: only one named as
     * the bash, zsh and fish hooks name it ({@code jkit-env.XXXXXX} right in $TMPDIR, else /tmp), so a
     * variable that points anywhere else never gets a file deleted. Null otherwise.
     */
    static Path hookEnvFile(Map<String, String> env) {
        Path file = Fs.path(env.get("JKIT_ENV_FILE"));
        if (file == null || file.getFileName() == null
                || !file.getFileName().toString().matches("jkit-env\\.[A-Za-z0-9]{6,}")) {
            return null;
        }
        String tmpdir = env.get("TMPDIR");
        Path tmp = Fs.path(tmpdir == null || tmpdir.isEmpty() ? "/tmp" : tmpdir);
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (tmp == null || parent == null || !parent.equals(tmp.toAbsolutePath().normalize())
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return file;
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
