package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolSpecTest {

    @Test
    void parsesToolAndVersion() {
        assertThat(ToolSpec.parse("jdk@21")).isEqualTo(new ToolSpec(Tool.JDK, "21"));
        assertThat(ToolSpec.parse("java@21.0.5").tool()).isEqualTo(Tool.JDK);
        assertThat(ToolSpec.parse("maven")).isEqualTo(new ToolSpec(Tool.MAVEN, null));
        assertThat(ToolSpec.parse("gradle@latest").version()).isNull();
        assertThat(ToolSpec.parse("graalvm@25").major()).isEqualTo(25);
    }

    @Test
    void findLooksAToolUpWithoutThrowing() {
        assertThat(Tool.find("Java")).contains(Tool.JDK);
        assertThat(Tool.find("mvn")).contains(Tool.MAVEN);
        assertThat(Tool.find("graalvm")).contains(Tool.GRAALVM);
        assertThat(Tool.find("zulu")).isEmpty();
        assertThat(Tool.find("")).isEmpty();
        assertThat(Tool.parse("SBT")).isEqualTo(Tool.SBT);
        assertThatThrownBy(() -> Tool.parse("zulu")).isInstanceOf(JkitException.class).hasMessage("unknown tool 'zulu'");
    }

    @Test
    void ltsIsAJdkWord() {
        assertThat(ToolSpec.parse("jdk@LTS").isLts()).isTrue();
        assertThat(ToolSpec.parse("graalvm@lts").isLts()).isTrue();
        assertThatThrownBy(() -> ToolSpec.parse("maven@lts")).hasMessageContaining("only JDKs have LTS releases");
        assertThat(java.util.stream.IntStream.rangeClosed(8, 30).filter(ToolSpec::isLtsMajor).boxed().toList())
                .containsExactly(8, 11, 17, 21, 25, 29);
    }

    @Test
    void unknownToolsGetAHintForTheUsualMistakes() {
        assertThatThrownBy(() -> ToolSpec.parse("21")).isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).hints()).first().asString().contains("jdk@21"));
        assertThatThrownBy(() -> ToolSpec.parse("java17"))
                .satisfies(e -> assertThat(((JkitException) e).hints()).contains("did you mean: java@17?"));
        assertThatThrownBy(() -> ToolSpec.parse("temurin@21"))
                .satisfies(e -> assertThat(((JkitException) e).hints()).first().asString()
                        .contains("it goes after the version: jdk@21 temurin"));
        assertThatThrownBy(() -> ToolSpec.parse("graal@25"))
                .satisfies(e -> assertThat(((JkitException) e).hints()).contains("did you mean: graalvm?"));
    }

    @Test
    void rejectsUnknownTools() {
        assertThatThrownBy(() -> ToolSpec.parse("kotlin@2"))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("unknown tool 'kotlin'");
    }

    @Test
    void graalVmSharesJdkInstallsButStaysOffPath() {
        assertThat(Tool.GRAALVM.storeTool()).isEqualTo(Tool.JDK);
        assertThat(Tool.GRAALVM.onPath()).isFalse();
        assertThat(Tool.GRAALVM.homeVariable()).isEqualTo("GRAALVM_HOME");
    }
}
