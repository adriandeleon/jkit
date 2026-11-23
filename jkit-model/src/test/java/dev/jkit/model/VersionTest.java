package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void ordersByComponentsThenBuild() {
        List<String> sorted = Stream.of("21.0.12+8", "21.0.13+9", "21.0.12.1+1", "17.0.21+9", "21.0.13+11")
                .map(Version::parse).sorted().map(Version::raw).toList();
        assertThat(sorted).containsExactly("17.0.21+9", "21.0.12+8", "21.0.12.1+1", "21.0.13+9", "21.0.13+11");
    }

    @Test
    void preReleasesSortBeforeTheRelease() {
        assertThat(Version.parse("4.0.0-rc-7")).isLessThan(Version.parse("4.0.0"));
        assertThat(Version.parse("4.0.0-rc-7").isPreRelease()).isTrue();
        assertThat(Version.parse("9.0.0-milestone-3").isPreRelease()).isTrue();
        assertThat(Version.parse("26-ea+12").isPreRelease()).isTrue();
        assertThat(Version.parse("3.9.11").isPreRelease()).isFalse();
    }

    @Test
    void matchesByComponentPrefix() {
        Version v = Version.parse("21.0.13+9");
        assertThat(v.matches(null)).isTrue();
        assertThat(v.matches("21")).isTrue();
        assertThat(v.matches("21.0")).isTrue();
        assertThat(v.matches("21.0.13")).isTrue();
        assertThat(v.matches("21.0.13+9")).isTrue();
        assertThat(v.matches("21.0.1")).isFalse();
        assertThat(v.matches("2")).isFalse();
        assertThat(Version.parse("3.10.1").matches("3.1")).isFalse();
        assertThat(v.matches("21.99999999999999999999")).isFalse(); // not cut down to 21
    }

    @Test
    void parsesJava8UpdateStyle() {
        assertThat(Version.parse("8u472").part(2)).isEqualTo(472);
        assertThat(Version.parse("8u472").matches("8")).isTrue();
    }
}
