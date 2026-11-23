package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SafeOutputTest {
    private static String through(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new SafeOutput(bytes), true, StandardCharsets.UTF_8);
        out.print(text);
        out.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void escapeSequencesFromProjectFilesNeverReachTheTerminal() {
        // OSC title + SGR from a cloned repo's jkit.toml vendor
        assertThat(through("jdk 21 zulu\u001b]0;pwned\u0007\u001b[31m"))
                .isEqualTo("jdk 21 zulu\\x1b]0;pwned\\x07\\x1b[31m");
        assertThat(through("a\u009b2Jb")).isEqualTo("a\\x9b2Jb"); // C1 CSI
        assertThat(through("del\u007f")).isEqualTo("del\\x7f");
    }

    @Test
    void ordinaryTextPassesUntouched() {
        String text = "Adrián → jdk 21.0.9+10 temurin\t✓\r\n  ½ ©";
        assertThat(through(text)).isEqualTo(text);
    }
}
