package dev.jkit.cli;

import dev.jkit.sources.Http;
import java.io.PrintStream;

/** A one-line download progress on stderr, only when attached to a terminal. */
final class ProgressBar implements Http.Progress {
    private final PrintStream err;
    private final boolean enabled = System.console() != null;
    private int lastPercent = -1;

    ProgressBar(PrintStream err) {
        this.err = err;
    }

    @Override
    public void update(long done, long total) {
        if (!enabled || total <= 0) {
            return;
        }
        int percent = (int) (done * 100 / total);
        if (percent != lastPercent) {
            lastPercent = percent;
            err.printf("\r  %3d%%  %d / %d MB", percent, done >> 20, total >> 20);
            if (done >= total) {
                err.println();
            }
        }
    }
}
