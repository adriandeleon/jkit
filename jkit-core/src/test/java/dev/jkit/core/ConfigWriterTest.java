package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigWriterTest {
    private static final Path FILE = Path.of("/u/.jkit/config.toml");

    private static ConfigWriter.Edit on(String text) {
        return ConfigWriter.set(FILE, text, "shell", "auto_switch", "true");
    }

    @Test
    void anEmptyFileGetsTheTable() {
        assertThat(on("").text()).isEqualTo("[shell]\nauto_switch = true\n");
    }

    @Test
    void addsTheTableAfterTheRestAndKeepsComments() {
        String text = "# mine\n[jdk]\nvendor = \"zulu\" # work\n\n";
        assertThat(on(text).text()).isEqualTo("# mine\n[jdk]\nvendor = \"zulu\" # work\n\n[shell]\nauto_switch = true\n");
    }

    @Test
    void addsTheKeyToAnExistingTable() {
        String text = "[shell]\nother = 1\n\n[jdk]\nvendor = \"zulu\"\n";
        assertThat(on(text).text()).isEqualTo("[shell]\nother = 1\nauto_switch = true\n\n[jdk]\nvendor = \"zulu\"\n");
    }

    @Test
    void flipsFalseInPlaceKeepingItsComment() {
        assertThat(on("[shell]\n  auto_switch = false   # not yet\n").text())
                .isEqualTo("[shell]\n  auto_switch = true   # not yet\n");
    }

    @Test
    void alreadyOnLeavesTheFileExactlyAsItIs() {
        ConfigWriter.Edit edit = on("[shell]\nauto_switch=true\n");
        assertThat(edit.changed()).isFalse();
        assertThat(edit.text()).isEqualTo("[shell]\nauto_switch=true\n");
    }

    @Test
    void refusesLayoutsItCantEditAndBrokenFiles() {
        assertThatThrownBy(() -> on("shell.auto_switch = false\n")).isInstanceOf(JkitException.class)
                .hasMessageContaining("can't set shell.auto_switch");
        assertThatThrownBy(() -> on("[shell\n")).isInstanceOf(JkitException.class).hasMessageContaining("invalid");
    }

    @Test
    void keepsWindowsLineEndings() {
        assertThat(on("[jdk]\r\nvendor = \"zulu\"\r\n").text())
                .isEqualTo("[jdk]\r\nvendor = \"zulu\"\r\n\r\n[shell]\r\nauto_switch = true\r\n");
    }
}
