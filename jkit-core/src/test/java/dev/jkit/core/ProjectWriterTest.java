package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectWriterTest {
    private static final Path FILE = Path.of("/p/jkit.toml");

    private static PinnedTool pin(Tool tool, String version) {
        return new PinnedTool(tool, version, null, false);
    }

    @Test
    void freshFileReadsBack() {
        String text = ProjectWriter.fresh(pin(Tool.JDK, "21"));
        assertThat(text).contains("[tools]\njdk = \"21\"\n");
        assertThat(ProjectFile.readToml(FILE, text).get(Tool.JDK)).isEqualTo(pin(Tool.JDK, "21"));
    }

    @Test
    void replacesTheLineInPlaceKeepingCommentsOrderAndAlias() {
        String text = """
                # build toolchain
                [tools]
                java = "17"   # LTS
                maven = "3.9"

                [other]
                x = 1
                """;
        ProjectWriter.Edit edit = ProjectWriter.set(FILE, text, pin(Tool.JDK, "21"));
        assertThat(edit.replaced()).isTrue();
        assertThat(edit.text()).isEqualTo(text.replace("java = \"17\"   # LTS", "java = \"21\"   # LTS"));
    }

    @Test
    void addsAfterTheLastKeyOfTools() {
        String text = "[tools]\njdk = \"21\"\n\n[other]\nx = 1\n";
        ProjectWriter.Edit edit = ProjectWriter.set(FILE, text, pin(Tool.GRADLE, "8.14.3"));
        assertThat(edit.replaced()).isFalse();
        assertThat(edit.text()).isEqualTo("[tools]\njdk = \"21\"\ngradle = \"8.14.3\"\n\n[other]\nx = 1\n");
    }

    @Test
    void addsAToolsTableWhenThereIsNone() {
        String text = "# nothing yet\n\n";
        assertThat(ProjectWriter.set(FILE, text, pin(Tool.MAVEN, "3.9")).text())
                .isEqualTo("# nothing yet\n\n[tools]\nmaven = \"3.9\"\n");
    }

    @Test
    void vendorAndFxUseTheLongForm() {
        PinnedTool zulu = new PinnedTool(Tool.JDK, "21.0.9+10", Vendor.of("zulu"), true);
        assertThat(ProjectWriter.value(zulu)).isEqualTo("{ version = \"21.0.9+10\", vendor = \"zulu\", fx = true }");
        String text = ProjectWriter.set(FILE, "[tools]\njdk = { version = \"17\" }\n", zulu).text();
        assertThat(ProjectFile.readToml(FILE, text).get(Tool.JDK)).isEqualTo(zulu);
    }

    @Test
    void keepsWindowsLineEndings() {
        String text = "[tools]\r\njdk = \"17\"\r\n";
        assertThat(ProjectWriter.set(FILE, text, pin(Tool.JDK, "21")).text()).isEqualTo("[tools]\r\njdk = \"21\"\r\n");
    }

    @Test
    void removesOnlyThatLine() {
        String text = "[tools]\njdk = \"21\" # keep?\nmaven = \"3.9\"\n";
        ProjectWriter.Edit edit = ProjectWriter.remove(FILE, text, Tool.JDK);
        assertThat(edit.replaced()).isTrue();
        assertThat(edit.text()).isEqualTo("[tools]\nmaven = \"3.9\"\n");

        ProjectWriter.Edit none = ProjectWriter.remove(FILE, edit.text(), Tool.GRADLE);
        assertThat(none.replaced()).isFalse();
        assertThat(none.text()).isEqualTo(edit.text());
    }

    @Test
    void refusesLayoutsItCantEditSafely() {
        String subtable = "[tools]\nmaven = \"3.9\"\n\n[tools.jdk]\nversion = \"17\"\n";
        assertThatThrownBy(() -> ProjectWriter.set(FILE, subtable, pin(Tool.JDK, "21")))
                .isInstanceOf(JkitException.class).hasMessageContaining("can't edit jdk");
        String dotted = "tools.jdk = \"17\"\n";
        assertThatThrownBy(() -> ProjectWriter.set(FILE, dotted, pin(Tool.JDK, "21")))
                .isInstanceOf(JkitException.class).hasMessageContaining("can't edit jdk");
    }

    @Test
    void aHashInsideAStringIsNotAComment() {
        assertThat(ProjectWriter.trailingComment("jdk = \"a#b\"  # real", 5)).isEqualTo("  # real");
        assertThat(ProjectWriter.trailingComment("jdk = 'a#b'", 5)).isEmpty();
    }
}
