package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestTest {
    private final List<String> vendors = List.of("corretto", "temurin", "zulu", "liberica", "sapmachine", "graalvm-ce");

    @Test
    void findsCloseTypos() {
        assertThat(Suggest.closest("correto", vendors)).isEqualTo("corretto");
        assertThat(Suggest.closest("temurn", vendors)).isEqualTo("temurin");
        assertThat(Suggest.closest("Zulu", vendors)).isEqualTo("zulu");
        assertThat(Suggest.closest("sapmachin", vendors)).isEqualTo("sapmachine");
        assertThat(Suggest.closest("zuul", vendors)).isEqualTo("zulu");
        assertThat(Suggest.closest("liber", vendors)).isEqualTo("liberica");
    }

    @Test
    void ignoresUnrelatedNames() {
        assertThat(Suggest.closest("foo", vendors)).isNull();
        assertThat(Suggest.closest("adoptium", vendors)).isNull();
    }
}
