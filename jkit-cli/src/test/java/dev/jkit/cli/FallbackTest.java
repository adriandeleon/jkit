package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** What the Windows console's code pages (and LANG=C) can't show is written as ASCII, not as ?. */
class FallbackTest {
    private static final Charset CP437 = Charset.forName("IBM437");

    private static String print(Charset charset, boolean json, String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, Fallback.of(charset, json))) {
            out.print(text);
        }
        return bytes.toString(charset);
    }

    @Test
    void jkitsGlyphsBecomeAsciiInCodePage437() {
        assertThat(print(CP437, false, "installed temurin 21 \u2192 ~/.jkit/tools/jdk/21"))
                .isEqualTo("installed temurin 21 -> ~/.jkit/tools/jdk/21");
        assertThat(print(CP437, false, "\u2713 \u25cb \u25cf \u203e \u2193 \u2026 \u2013"))
                .isEqualTo("+ o * ~ v ... -");
        // what 437 has stays as it is: \u00e9, \u00b7 and box drawing
        assertThat(print(CP437, false, "Jos\u00e9 \u00b7 \u2500")).isEqualTo("Jos\u00e9 \u00b7 \u2500");
        // anything else is still ?, a character past U+FFFF once, not once per half
        assertThat(print(CP437, false, "a\u4e2d\uD83D\uDE00b")).isEqualTo("a??b");
    }

    @Test
    void asciiOnlyDropsAccentsToo() {
        assertThat(print(StandardCharsets.US_ASCII, false, "Adri\u00e1n De Le\u00f3n \u00b7 \u00c7a"))
                .isEqualTo("Adrian De Leon - Ca");
        assertThat(Fallback.LATIN1_LETTERS).hasSize(64);
    }

    /** A pair split across two writes is still one character. */
    @Test
    void aSurrogatePairSplitAcrossWritesStaysWhole() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, Fallback.of(CP437, true))) {
            out.print("\uD83D");
            out.print("\uDE00");
        }
        assertThat(bytes.toString(CP437)).isEqualTo("\\ud83d\\ude00");
    }

    /** --json: an escape is the same character in the document; a stand-in would change the data. */
    @Test
    void jsonGetsEscapesThatReadBackAsTheSameText() throws Exception {
        String doc = "{\"home\" : \"C:\\\\Users\\\\Jos\u00e9\\\\\u4e2d\", \"note\" : \"a \u2192 b\"}";
        String written = print(CP437, true, doc);
        assertThat(written).contains("Jos\u00e9").contains("\\u4e2d").contains("\\u2192");
        assertThat(new ObjectMapper().readTree(written).get("home").asText()).isEqualTo("C:\\Users\\Jos\u00e9\\\u4e2d");
        assertThat(new ObjectMapper().readTree(written).get("note").asText()).isEqualTo("a \u2192 b");
    }

    @Test
    void utf8IsLeftAsItIs() {
        assertThat(Fallback.of(StandardCharsets.UTF_8, false)).isSameAs(StandardCharsets.UTF_8);
        assertThat(Fallback.of(StandardCharsets.UTF_16LE, true)).isSameAs(StandardCharsets.UTF_16LE);
        Charset once = Fallback.of(CP437, false);
        assertThat(Fallback.of(once, false)).isSameAs(once);
    }

    /** SafeOutput's escapes still apply over a console code page. */
    @Test
    void safeOutputUsesTheFallback() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream console = new PrintStream(bytes, true, CP437);
        PrintStream out = SafeOutput.wrap(console);
        out.print("a \u2192 b\u001b[2J");
        out.flush();
        assertThat(bytes.toString(CP437)).isEqualTo("a -> b\\x1b[2J");
    }
}
