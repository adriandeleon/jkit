package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShellQuoteTest {

    @Test
    void shellQuotingPerShell() {
        assertThat(ShellQuote.quote("bash", "a'b")).isEqualTo("'a'\\''b'");
        assertThat(ShellQuote.quote("fish", "a'b\\")).isEqualTo("'a\\'b\\\\'");
        assertThat(ShellQuote.quote("pwsh", "C:\\O'Brien")).isEqualTo("'C:\\O''Brien'");
        assertThat(ShellQuote.quote("pwsh", "a\u2019b")).isEqualTo("'a\u2019\u2019b'");
    }

    @Test
    void scriptQuotingIsAsciiForPowerShell() {
        assertThat(ShellQuote.script("pwsh", "C:\\O'Brien")).isEqualTo("'C:\\O''Brien'");
        assertThat(ShellQuote.script("pwsh", "C:\\Users\\Jos\u00e9\\.jkit"))
                .isEqualTo("('C:\\Users\\Jos' + [char]0x00E9 + '\\.jkit')");
        assertThat(ShellQuote.script("powershell", "\u00e9")).isEqualTo("('' + [char]0x00E9)");
        // a typographic quote is a character like any other here, not a quote to double
        assertThat(ShellQuote.script("pwsh", "a\u2019'b\uD83D\uDE00"))
                .isEqualTo("('a' + [char]0x2019 + '''b' + [char]0xD83D + [char]0xDE00)");
        assertThat(ShellQuote.script("pwsh", "")).isEqualTo("''");
        // the other shells take the bytes as they are
        assertThat(ShellQuote.script("bash", "/home/jos\u00e9")).isEqualTo("'/home/jos\u00e9'");
        assertThat(ShellQuote.script("fish", "/home/jos\u00e9")).isEqualTo("'/home/jos\u00e9'");
    }
}
