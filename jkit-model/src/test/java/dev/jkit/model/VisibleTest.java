package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VisibleTest {
    @Test
    void lineBreaksAndBidiOverridesAreShownNotObeyed() {
        assertThat(Visible.of("/src/x\nok    all good")).isEqualTo("/src/x\\x0aok    all good");
        assertThat(Visible.of("a\rb‮cba")).isEqualTo("a\\x0db\\u{202e}cba");
        assertThat(Visible.of("plain/ünïcode")).isEqualTo("plain/ünïcode");
        assertThat(Visible.of(null)).isNull();
        assertThat(Visible.of("a\u200Bb\uFEFF")).isEqualTo("a\\u{200b}b\\u{feff}");
        assertThat(Version.isSafeText("21.0.9+10")).isTrue();
        assertThat(Version.isSafeText("3.9.99.:.")).isFalse();
        assertThat(Version.isSafeText("1.0\nok")).isFalse();
    }
}
