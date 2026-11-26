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
}
