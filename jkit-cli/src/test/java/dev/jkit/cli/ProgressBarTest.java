package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProgressBarTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private long now;

    private ProgressBar bar(boolean terminal) {
        return new ProgressBar(new PrintStream(out, true, StandardCharsets.UTF_8), terminal, () -> now);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutATerminalItPrintsEachQuarterAndWhenQuietTooLong() {
        ProgressBar bar = bar(false);
        long mb = 1 << 20;
        for (long done = 0; done <= 200 * mb; done += mb) {
            bar.update(done, 200 * mb);
        }
        assertThat(said()).isEqualTo("   25%  50 / 200 MB\n   50%  100 / 200 MB\n   75%  150 / 200 MB\n")
                .doesNotContain("\r");
        out.reset();
        // a slow stretch: a line every 30 s even without a new quarter
        bar = bar(false);
        bar.update(1 * mb, 200 * mb);
        now += ProgressBar.QUIET_NANOS;
        bar.update(2 * mb, 200 * mb);
        bar.update(3 * mb, 200 * mb);
        assertThat(said()).isEqualTo("    1%  2 / 200 MB\n");
        out.reset();
        // no length: megabytes so far
        now += ProgressBar.QUIET_NANOS;
        bar(false).update(5 * mb, -1);
        now += ProgressBar.QUIET_NANOS;
        ProgressBar unknown = bar(false);
        now += ProgressBar.QUIET_NANOS;
        unknown.update(7 * mb, -1);
        assertThat(said()).isEqualTo("  7 MB\n");
    }

    @Test
    void aRestartIsSaidOnALineOfItsOwn() {
        ProgressBar bar = bar(true);
        bar.update(10, 100);
        bar.restarting("slow download (90 KB/s, about 40 min to go)");
        assertThat(said()).isEqualTo("\r   10%  0 / 0 MB\n  slow download (90 KB/s, about 40 min to go); starting again on a new connection\n");
    }
}
