package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Version, spec and platform edge cases found in the review. */
class ReviewFixesTest {

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

    @Test
    void javaEightCanBeWrittenTheOldWay() {
        assertThat(ToolSpec.parse("jdk@1.8").version()).isEqualTo("8");
        assertThat(ToolSpec.parse("jdk@1.8").major()).isEqualTo(8);
        assertThat(ToolSpec.parse("jdk@1.8.0_432").version()).isEqualTo("8.0_432");
        assertThat(Version.parse("8.0.432+6").matches("8.0_432")).isTrue();
        assertThat(ToolSpec.parse("gradle@1.8").version()).isEqualTo("1.8"); // a real Gradle version
        assertThat(new PinnedTool(Tool.JDK, "1.8", null, false).version()).isEqualTo("8");
    }

    @Test
    void specsAreTrimmedAndChecked() {
        assertThat(ToolSpec.parse(" jdk@21").tool()).isEqualTo(Tool.JDK);
        assertThatThrownBy(() -> ToolSpec.parse("jdk@abc"))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("is not a version");
    }

    @Test
    void sdkmanEarlyAccessIdsKeepTheBuild() {
        PinnedTool pin = SdkmanIds.parse(Tool.JDK, "26.ea.20-open");
        assertThat(pin.version()).isEqualTo("26-ea+20");
        assertThat(Version.parse(pin.version()).isPreRelease()).isTrue();
        assertThat(Version.parse("26-ea+20").matches(pin.version())).isTrue();
        assertThat(Version.parse("26-ea+19").matches(pin.version())).isFalse();
    }

    @Test
    void darwinIsMacNotWindows() {
        assertThat(Platform.of("Darwin", "aarch64")).isEqualTo(Platform.MACOS_AARCH64);
        assertThat(Platform.of("Windows 11", "amd64")).isEqualTo(Platform.WINDOWS_X64);
    }

    @Test
    void shellQuotingPerShell() {
        assertThat(ShellQuote.quote("bash", "a'b")).isEqualTo("'a'\\''b'");
        assertThat(ShellQuote.quote("fish", "a'b\\")).isEqualTo("'a\\'b\\\\'");
        assertThat(ShellQuote.quote("pwsh", "C:\\O'Brien")).isEqualTo("'C:\\O''Brien'");
        assertThat(ShellQuote.quote("pwsh", "a\u2019b")).isEqualTo("'a\u2019\u2019b'");
    }
}
