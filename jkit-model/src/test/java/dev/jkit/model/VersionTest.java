package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class VersionTest {

    @Test
    void ordersByComponentsThenBuild() {
        List<String> sorted = Stream.of("21.0.12+8", "21.0.13+9", "21.0.12.1+1", "17.0.21+9", "21.0.13+11")
                .map(Version::parse).sorted().map(Version::raw).toList();
        assertThat(sorted).containsExactly("17.0.21+9", "21.0.12+8", "21.0.12.1+1", "21.0.13+9", "21.0.13+11");
    }

    /** Version lists as the sources publish them, each in ascending order. */
    static Stream<Arguments> realWorldLists() {
        return Stream.of(
                Arguments.of("jdk", List.of("8u392", "8u402", "11.0.22+7", "17.0.10+7", "21.0.5+11", "21.0.6+7",
                        "22-ea", "22-ea+27", "22", "22.0.1+8", "25.0.1+8", "25.0.1+8-LTS")),
                Arguments.of("corretto", List.of("17.0.9.8.1", "17.0.10.0.1", "17.0.10.7.1", "17.0.11.9.1")),
                Arguments.of("maven", List.of("3.9.9", "3.9.10", "4.0.0-alpha-13", "4.0.0-beta-5", "4.0.0-rc-4",
                        "4.0.0-rc-10", "4.0.0")),
                Arguments.of("gradle", List.of("8.13", "8.14-milestone-2", "8.14-rc-1", "8.14-rc-2", "8.14", "8.14.1",
                        "9.0.0-rc-1", "9.0.0")),
                Arguments.of("sbt", List.of("1.9.9", "1.10.0-M1", "1.10.0-RC1", "1.10.0-RC2", "1.10.0", "1.10.1",
                        "1.10.10")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("realWorldLists")
    void sortsRealWorldLists(String name, List<String> ascending) {
        List<String> shuffled = new ArrayList<>(ascending);
        Collections.shuffle(shuffled, new Random(42));
        assertThat(shuffled.stream().map(Version::parse).sorted().map(Version::raw).toList())
                .containsExactlyElementsOf(ascending);
        assertThat(shuffled.stream().map(Version::parse).sorted(Comparator.reverseOrder())
                .map(Version::raw).toList()).containsExactlyElementsOf(ascending.reversed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("realWorldLists")
    void compareToIsConsistentWithEquals(String name, List<String> versions) {
        for (String a : versions) {
            for (String b : versions) {
                Version va = Version.parse(a);
                Version vb = Version.parse(b);
                assertThat(va.compareTo(vb) == 0).as("%s vs %s", a, b).isEqualTo(va.equals(vb));
                assertThat(Integer.signum(va.compareTo(vb))).as("%s vs %s", a, b)
                        .isEqualTo(-Integer.signum(vb.compareTo(va)));
            }
        }
        // same numbers, different text: still not equal, and still ordered
        Version upper = Version.parse("1.10.0-RC1");
        Version lower = Version.parse("1.10.0-rc1");
        assertThat(upper).isNotEqualTo(lower);
        assertThat(upper.compareTo(lower)).isNotZero();
        assertThat(Version.parse("8u402").compareTo(Version.parse("8.0.402"))).isNotZero();
    }

    @Test
    void java8LegacyNumberingSortsWithUpdates() {
        // 1.8.0_402 is how java -version and old project files write 8u402
        Version legacy = Version.parse(Version.fromLegacyJava("1.8.0_402"));
        Version update = Version.parse("8u402");
        assertThat(legacy.part(0)).isEqualTo(8);
        assertThat(legacy.part(2)).isEqualTo(402);
        assertThat(legacy).isGreaterThan(Version.parse("8u392")).isLessThan(Version.parse("8u412"));
        assertThat(update.matches("8.0.402")).isTrue();
        assertThat(legacy.matches("8")).isTrue();
        assertThat(Version.fromLegacyJava("1.8")).isEqualTo("8");
        assertThat(Version.fromLegacyJava("1.10")).isEqualTo("1.10"); // not Java's old numbering
    }

    @Test
    void preReleasesSortBeforeTheRelease() {
        assertThat(Version.parse("4.0.0-rc-7")).isLessThan(Version.parse("4.0.0"));
        assertThat(Version.parse("4.0.0-rc-7").isPreRelease()).isTrue();
        assertThat(Version.parse("9.0.0-milestone-3").isPreRelease()).isTrue();
        assertThat(Version.parse("26-ea+12").isPreRelease()).isTrue();
        assertThat(Version.parse("3.9.11").isPreRelease()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"26-ea", "26-ea+12", "26-EA+12", "4.0.0-alpha-13", "4.0.0-beta-5", "4.0.0-rc-4",
            "4.0.0-RC-4", "0.4.0-rc.1", "1.10.0-RC1", "1.10.0-M1", "8.14-milestone-2", "3.10.0-SNAPSHOT",
            "0.5.0-dev.3", "0.9-preview-3", "2.0.0-pre", "2.0.0-pre.1", "8.15-nightly", "1.0beta2"})
    void preReleases(String raw) {
        assertThat(Version.parse(raw).isPreRelease()).isTrue();
        assertThat(Version.parse(raw)).isLessThan(Version.parse(raw.replaceFirst("[-a-zA-Z].*", "")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"21.0.5+11", "25.0.1+8-LTS", "21.0.5+11-src1", "17.0.10.0.1", "8u402", "3.9.11",
            "8.14.1", "1.10.10", "21.0.5+11-Ubuntu-1ubuntu1", "21.0.2+13-58", "11.0.22+7-alpine"})
    void releases(String raw) {
        assertThat(Version.parse(raw).isPreRelease()).isFalse();
    }

    @Test
    void matchesByComponentPrefix() {
        Version v = Version.parse("21.0.13+9");
        assertThat(v.matches(null)).isTrue();
        assertThat(v.matches("")).isTrue();
        assertThat(v.matches("latest")).isTrue();
        assertThat(v.matches("21")).isTrue();
        assertThat(v.matches("21.0")).isTrue();
        assertThat(v.matches("21.0.13")).isTrue();
        assertThat(v.matches("21.0.13+9")).isTrue();
        assertThat(v.matches("21.0.13+8")).isFalse();
        assertThat(v.matches("21.0.1")).isFalse();
        assertThat(v.matches("2")).isFalse();
        assertThat(v.matches("abc")).isFalse();
        assertThat(Version.parse("3.10.1").matches("3.1")).isFalse();
        assertThat(v.matches("21.99999999999999999999")).isFalse(); // not cut down to 21
    }

    @Test
    void matchesEdgeCases() {
        // missing components count as 0
        assertThat(Version.parse("21+35").matches("21.0.0")).isTrue();
        assertThat(Version.parse("21.0.5.1+2").matches("21.0.5")).isTrue();
        // a pre-release spec matches only that tag, case aside
        assertThat(Version.parse("4.0.0-rc-7").matches("4.0.0-rc-7")).isTrue();
        assertThat(Version.parse("4.0.0-rc-7").matches("4.0.0-rc-4")).isFalse();
        assertThat(Version.parse("4.0.0-rc-7").matches("4.0.0-RC-7")).isTrue();
        assertThat(Version.parse("4.0.0").matches("4.0.0-rc-7")).isFalse();
        // a release spec matches its pre-releases too: callers filter those unless --pre
        assertThat(Version.parse("4.0.0-rc-7").matches("4.0.0")).isTrue();
        // the build number: a prefix of it, and the -LTS label doesn't get in the way
        assertThat(Version.parse("25.0.1+8-LTS").matches("25.0.1+8")).isTrue();
        assertThat(Version.parse("25.0.1+8-LTS").matches("25.0.1+9")).isFalse();
        assertThat(Version.parse("22-ea+27").matches("22-ea+27")).isTrue();
        assertThat(Version.parse("22-ea+27").matches("22-ea+26")).isFalse();
        // Corretto's fourth and fifth numbers
        assertThat(Version.parse("17.0.10.7.1").matches("17.0.10")).isTrue();
        assertThat(Version.parse("17.0.10.7.1").matches("17.0.10.0")).isFalse();
        // sbt: 1.10 is not 1.1
        assertThat(Version.parse("1.10.10").matches("1.1")).isFalse();
        assertThat(Version.parse("1.10.10").matches("1.10.1")).isFalse();
    }

    @Test
    void parsesJava8UpdateStyle() {
        assertThat(Version.parse("8u472").part(2)).isEqualTo(472);
        assertThat(Version.parse("8u472").matches("8")).isTrue();
    }

    @Test
    void exactPreReleaseSpecMatchesOnlyThatTag() {
        assertThat(Version.parse("4.0.0-rc-7").matches("4.0.0-rc-4")).isFalse();
        assertThat(Version.parse("4.0.0-rc-4").matches("4.0.0-rc-4")).isTrue();
        assertThat(Version.parse("4.0.0-rc-7").matches("4.0.0-alpha-10")).isFalse();
        assertThat(Version.parse("8.10.2").matches("8.10-rc-1")).isFalse();
        assertThat(Version.parse("26-ea+20").matches("26-ea")).isTrue();
        assertThat(Version.parse("4.0.0-rc-7").matches("4")).isTrue(); // a plain prefix still matches
    }

    @Test
    void preReleasesSortByNumberNotText() {
        List<String> sorted = Stream.of("4.0.0-rc-2", "4.0.0-rc-10", "4.0.0-alpha-13", "4.0.0-alpha-9", "4.0.0-beta-1")
                .map(Version::parse).sorted().map(Version::raw).toList();
        assertThat(sorted).containsExactly("4.0.0-alpha-9", "4.0.0-alpha-13", "4.0.0-beta-1", "4.0.0-rc-2", "4.0.0-rc-10");
        assertThat(Version.parse("9.0.0-milestone-10")).isGreaterThan(Version.parse("9.0.0-milestone-9"));
    }

    @Test
    void missingComponentsCountAsZero() {
        assertThat(Version.parse("21+35").matches("21.0.0")).isTrue();
        assertThat(Version.parse("21+35").matches("21.0.1")).isFalse();
        assertThat(Version.parse("21.0.5+11").matches("21.0")).isTrue();
    }
}
