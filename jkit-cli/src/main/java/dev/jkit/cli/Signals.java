package dev.jkit.cli;

import java.util.ArrayList;
import java.util.List;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/** Ignores terminal signals while jkit waits for a child that receives them itself. */
final class Signals {
    record Saved(Signal signal, SignalHandler previous) { }

    private Signals() { }

    /** Ignores each signal this platform has; returns what to restore. */
    static List<Saved> ignore(String... names) {
        List<Saved> saved = new ArrayList<>();
        for (String name : names) {
            try {
                Signal signal = new Signal(name);
                saved.add(new Saved(signal, Signal.handle(signal, SignalHandler.SIG_IGN)));
            } catch (IllegalArgumentException | UnsupportedOperationException notHere) {
                // no such signal on this OS (QUIT on Windows), or the VM reserves it
            }
        }
        return saved;
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
