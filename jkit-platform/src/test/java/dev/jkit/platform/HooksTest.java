package dev.jkit.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HooksTest {
    @TempDir
    Path tmp;

    /** Runs the bash hook for a home whose path has a quote, a space and a $, and prints what it set. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookWorksWithAwkwardHomePaths() throws Exception {
        Path root = tmp.resolve("o'brien $HOME x");
        Files.createDirectories(root.resolve("global"));
        Files.createSymbolicLink(root.resolve("global/jdk"), Files.createDirectories(tmp.resolve("jdk")));
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, Hooks.posix("bash", root)
                + "\nprintf '%s\\n' \"$JAVA_HOME\" \"$PATH\"\n");
        Process p = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString())
                .redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        assertThat(out.lines().toList().getFirst()).isEqualTo(root.resolve("global/jdk").toString());
        assertThat(out).contains(root.resolve("global/jdk/bin").toString());
    }

    @Test
    void fishHookKeepsAnExistingJavaHomeAndSetsTheOtherHomes() {
        String fish = Hooks.fish(Path.of("/home/u/.jkit"));
        assertThat(fish).contains("if not set -q JAVA_HOME; and test -e $__jkit_g/jdk; set -gx JAVA_HOME $__jkit_g/jdk; end");
        assertThat(fish).contains("MAVEN_HOME").contains("GRADLE_HOME").contains("GRAALVM_HOME");
        assertThat(fish).doesNotContain("set -q JAVA_HOME; or");
    }

    @Test
    void powershellHookAddsTheGlobalsAndQuotesThePath() {
        String ps = Hooks.powershell(Path.of("C:\\Users\\O'Brien\\.jkit"));
        assertThat(ps).contains("'C:\\Users\\O''Brien\\.jkit").contains("$env:PATH = $bin")
                .contains("JAVA_HOME").contains("GRAALVM_HOME");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void unixLinksSwitchAndReadBack() throws Exception {
        UnixOs os = new UnixOs();
        Path a = Files.createDirectories(tmp.resolve("tools/a"));
        Path b = Files.createDirectories(tmp.resolve("tools/b"));
        Path link = tmp.resolve("global/jdk");
        os.pointLink(link, a);
        os.pointLink(link, b);
        assertThat(os.readLink(link)).isEqualTo(b);
        try (var files = Files.list(link.getParent())) {
            assertThat(files.map(f -> f.getFileName().toString()).toList()).containsExactly("jdk"); // no temp links left
        }
        assertThat(os.hook("pwsh", tmp)).contains("PowerShell");
    }
}
