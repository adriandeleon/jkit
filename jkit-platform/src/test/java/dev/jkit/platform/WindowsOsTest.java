package dev.jkit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The parts of WindowsOs that need no Windows. */
class WindowsOsTest {

    /** cmd /u writes mklink's messages as UTF-16LE, so a folder with an accent reads back as it is. */
    @Test
    void cmdOutputIsReadAsUtf16() {
        String message = "Junction created for C:\\Users\\Jos\u00e9\\.jkit\\global\\jdk <<===>> C:\\x\r\n";
        assertThat(WindowsOs.cmdOutput(message.getBytes(StandardCharsets.UTF_16LE))).isEqualTo(message);
        // output that isn't UTF-16 (no zero byte) is read in the console's charset: ASCII stays ASCII
        assertThat(WindowsOs.cmdOutput("Access is denied.\r\n".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("Access is denied.\r\n");
        assertThat(WindowsOs.cmdOutput(new byte[0])).isEmpty();
    }

    /** jkit hook bash in Git Bash: an older jkit's --install put that in ~/.bashrc, and it says how to take it out. */
    @Test
    void aUnixShellsHookSaysHowToTakeItOut() {
        assertThatThrownBy(() -> new WindowsOs().hook("bash", Path.of("root"), null))
                .isInstanceOf(JkitException.class).hasMessage("unsupported shell on Windows: bash")
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .contains("if your bash startup file runs this, take it out: jkit hook bash --uninstall"));
        assertThatThrownBy(() -> new WindowsOs().hook("nu", Path.of("root"), null))
                .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly("shells: pwsh, powershell"));
    }
}
