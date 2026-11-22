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

    /**
     * Runs the bash hook for a home whose path has a quote, a space and a $, and prints what it set;
     * under set -u, which some users keep on in interactive shells.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookWorksWithAwkwardHomePaths() throws Exception {
        Path root = tmp.resolve("o'brien $HOME x");
        Files.createDirectories(root.resolve("global"));
        Files.createSymbolicLink(root.resolve("global/jdk"), Files.createDirectories(tmp.resolve("jdk")));
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, "set -u\n" + Hooks.posix("bash", root)
                + "\ntype jkit > /dev/null\nprintf '%s\\n' \"$JAVA_HOME\" \"$PATH\"\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString())
                .redirectErrorStream(true);
        withoutToolHomes(pb); // the hook keeps a JAVA_HOME the build itself runs with
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        assertThat(out.lines().toList().getFirst()).isEqualTo(root.resolve("global/jdk").toString());
        assertThat(out).contains(root.resolve("global/jdk/bin").toString());
    }

    private static void withoutToolHomes(ProcessBuilder pb) {
        for (String v : new String[] {"JAVA_HOME", "MAVEN_HOME", "GRADLE_HOME", "SBT_HOME", "GRAALVM_HOME",
                "PROMPT_COMMAND", "JKIT_PROJECT", "JKIT_ENV_FILE"}) {
            pb.environment().remove(v);
        }
    }

    /**
     * An interactive bash with the auto-switch hook: jkit hook-env runs once when the hook loads and
     * again only when the directory changes, its output is evaluated, and $? survives the prompt.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashAutoSwitchRunsHookEnvOnDirectoryChangeOnly() throws Exception {
        Path root = tmp.resolve("home/.jkit");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path log = tmp.resolve("calls.log");
        Path fake = bin.resolve("jkit");
        Files.writeString(fake, "#!/bin/sh\necho \"$*\" >> '" + log + "'\n"
                + "[ \"$1\" = hook-env ] && echo \"export JKIT_TEST_DIR='$PWD'\"\nexit 0\n");
        fake.toFile().setExecutable(true);
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path hook = tmp.resolve("hook.sh");
        Files.writeString(hook, Hooks.posix("bash", root) + Hooks.autoSwitch("bash"));
        String input = ". '" + hook + "'\n"
                + "cd '" + a + "'\n"
                + "cd .\n"
                + "false\n"
                + "echo \"rc=$?\"\n"
                + "echo \"dir=$JKIT_TEST_DIR\"\n"
                + "exit\n";
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", "-i").directory(tmp.toFile());
        withoutToolHomes(pb);
        pb.environment().put("PATH", bin + ":" + pb.environment().get("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out).contains("rc=1").contains("dir=" + a);
        assertThat(Files.readAllLines(log)).containsExactly("hook-env bash", "hook-env bash");
    }

    @Test
    void autoSwitchHooksForZshAndFishAndNoneForPowerShell() {
        assertThat(Hooks.autoSwitch("zsh")).contains("add-zsh-hook chpwd __jkit_hook_env")
                .contains("command jkit hook-env zsh");
        assertThat(Hooks.autoSwitch("fish")).contains("--on-variable PWD").contains("command jkit hook-env fish | source");
        assertThat(Hooks.autoSwitch("pwsh")).isNull();
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
