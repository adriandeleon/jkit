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
