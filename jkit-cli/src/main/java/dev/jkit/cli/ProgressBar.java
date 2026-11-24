package dev.jkit.cli;

import dev.jkit.sources.Http;
import java.io.PrintStream;

/**
 * Download progress on stderr: on a terminal, one line that updates in place; otherwise (CI, a
 * log file) a line at each quarter and at least every 30 seconds, so a long download isn't silent.
 */
final class ProgressBar implements Http.Progress {
    /** Without a terminal, the longest jkit stays quiet during a download. */
    static final long QUIET_NANOS = 30_000_000_000L;

    private final PrintStream err;
    private final boolean terminal;
    private final java.util.function.LongSupplier clock;
    private int lastPercent = -1;
    private int lastQuarter;
    private long lastLine;
    /** Whether the in-place line is showing, so a message starts on a line of its own. */
    private boolean midLine;

    ProgressBar(PrintStream err) {
        this(err, System.console() != null, System::nanoTime);
    }

    ProgressBar(PrintStream err, boolean terminal, java.util.function.LongSupplier clock) {
        this.err = err;
        this.terminal = terminal;
        this.clock = clock;
        this.lastLine = clock.getAsLong();
    }

    @Override
    public void update(long done, long total) {
        if (terminal) {
            if (total <= 0) {
                return;
            }
            int percent = (int) (done * 100 / total);
            if (percent != lastPercent) {
                lastPercent = percent;
                err.printf("\r  %3d%%  %d / %d MB", percent, done >> 20, total >> 20);
                midLine = done < total;
                if (done >= total) {
                    err.println();
                }
            }
            return;
        }
        long now = clock.getAsLong();
        int quarter = total > 0 ? (int) (done * 4 / total) : 0;
        boolean step = total > 0 && quarter > lastQuarter && done < total;
        if (step || now - lastLine >= QUIET_NANOS) {
            lastQuarter = Math.max(lastQuarter, quarter);
            lastLine = now;
            if (total > 0) {
                err.printf("  %3d%%  %d / %d MB%n", (int) (done * 100 / total), done >> 20, total >> 20);
            } else {
                err.printf("  %d MB%n", done >> 20);
            }
        }
    }

    @Override
    public void restarting(String why) {
        if (midLine) {
            err.println();
            midLine = false;
        }
        err.println("  " + why + "; starting again on a new connection");
        lastPercent = -1;
        lastQuarter = 0;
        lastLine = clock.getAsLong();
    }
}
