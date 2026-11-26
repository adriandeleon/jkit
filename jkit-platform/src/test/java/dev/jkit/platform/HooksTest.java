package dev.jkit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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

    /** zsh, where installed: hook-env runs on cd in the shell itself, not in a $( ) subshell. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void zshAutoSwitchSkipsSubshells() throws Exception {
        Path zsh = Stream.of("/bin/zsh", "/usr/bin/zsh", "/usr/local/bin/zsh", "/opt/homebrew/bin/zsh")
                .map(Path::of).filter(Files::isExecutable).findFirst().orElse(null);
        assumeTrue(zsh != null, "zsh isn't installed");
        Path root = tmp.resolve("home/.jkit");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path log = tmp.resolve("calls.log");
        Path fake = bin.resolve("jkit");
        Files.writeString(fake, "#!/bin/sh\necho \"$* ${PWD##*/}\" >> '" + log + "'\nexit 0\n");
        fake.toFile().setExecutable(true);
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        Path script = tmp.resolve("hook.zsh");
        Files.writeString(script, Hooks.posix("zsh", root) + Hooks.autoSwitch("zsh")
                + "cd '" + a + "'\nx=$(cd '" + b + "'; pwd)\n( cd '" + b + "' )\n");
        ProcessBuilder pb = new ProcessBuilder(zsh.toString(), "-f", script.toString()).directory(tmp.toFile())
                .redirectErrorStream(true);
        withoutToolHomes(pb);
        pb.environment().put("PATH", bin + ":" + pb.environment().get("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        // once as the hook loads, once for the cd; neither subshell's cd
        assertThat(Files.readAllLines(log)).containsExactly("hook-env zsh " + tmp.getFileName(), "hook-env zsh a");
    }

    @Test
    void autoSwitchHooksForZshAndFishAndNoneForPowerShell() {
        assertThat(Hooks.autoSwitch("zsh")).contains("add-zsh-hook chpwd __jkit_hook_env")
                .contains("__jkit_run hook-env zsh")
                // not in a prompt theme's $( ) subshell, where what it prints lands in the prompt
                .contains("[[ $ZSH_SUBSHELL -eq 0 ]] || return 0");
        assertThat(Hooks.autoSwitch("fish")).contains("--on-variable PWD").contains("__jkit_run hook-env fish | source");
        assertThat(Hooks.autoSwitch("pwsh")).isNull();
    }

    @Test
    void hooksCallJkitByItsPathWhenKnown() {
        // a JDK's bin/jkit, first on PATH in a project, must not be what runs on cd
        String self = "/opt/jk it's/bin/jkit";
        assertThat(Hooks.posix("bash", Path.of("/home/u/.jkit"), self))
                .contains("__jkit_bin='/opt/jk it'\\''s/bin/jkit'")
                .contains("JKIT_ENV_FILE=\"$__jkit_env\" __jkit_run \"$@\"");
        assertThat(Hooks.posix("bash", Path.of("/home/u/.jkit"))).contains("__jkit_bin=''");
        assertThat(Hooks.autoSwitch("bash", self)).contains("eval \"$(__jkit_run hook-env bash)\"");
        assertThat(Hooks.fish(Path.of("/home/u/.jkit"), self)).contains("set -g __jkit_bin '/opt/jk it\\'s/bin/jkit'")
                .contains("set -lx JKIT_ENV_FILE $envfile");
        assertThat(Hooks.powershell(Path.of("/home/u/.jkit"), self)).contains("$global:__jkit_bin = '/opt/jk it''s/bin/jkit'");
    }

    /** The saved path runs while it exists; once it's gone (Scoop moved it), jkit on PATH does. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookFallsBackToPathWhenItsJkitIsGone() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path saved = Files.createDirectories(tmp.resolve("saved")).resolve("jkit");
        Path tool = Files.createDirectories(tmp.resolve("home/tools/jdk/21/bin"));
        for (Path p : new Path[] {bin.resolve("jkit"), saved, tool.resolve("jkit")}) {
            Files.writeString(p, "#!/bin/sh\necho \"" + p.getParent().getFileName() + " $JKIT_ENV_FILE\"\n");
            p.toFile().setExecutable(true);
        }
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, Hooks.posix("bash", tmp.resolve("home"), saved.toString())
                + "jkit a\nrm '" + saved + "'\njkit b\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString()).redirectErrorStream(true);
        withoutToolHomes(pb);
        // a project's JDK first on PATH: its bin/jkit is never the fallback
        pb.environment().put("PATH", tool + ":" + bin + ":" + pb.environment().get("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.lines().toList()).as(out).hasSize(2);
        assertThat(out.lines().toList().get(0)).startsWith("saved ").contains("jkit-env.");
        assertThat(out.lines().toList().get(1)).startsWith("bin ").contains("jkit-env.");
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

    /**
     * Runs the PowerShell hook under every PowerShell on this machine (Windows PowerShell 5.1 and
     * pwsh 7), with the execution policy at Restricted, Windows PowerShell's default: jkit use must
     * still change the shell, and a bare -- (which PowerShell drops from a function's arguments)
     * must still reach jkit, so that jkit exec works.
     */
    @Test
    void powershellHookPassesDashDashAndAppliesChangesUnderRestrictedPolicy() throws Exception {
        boolean windows = File.separatorChar == '\\';
        Path fake;
        if (windows) {
            fake = tmp.resolve("fakejkit.cmd");
            Files.writeString(fake, "@echo off\r\necho argv: %*\r\n"
                    + "if defined JKIT_ENV_FILE (echo $env:JAVA_HOME = 'C:\\x\\jdk17'>\"%JKIT_ENV_FILE%\")\r\n");
        } else {
            fake = tmp.resolve("fakejkit");
            Files.writeString(fake, "#!/bin/sh\nprintf 'argv:'; for a in \"$@\"; do printf ' %s' \"$a\"; done; echo\n"
                    + "[ -n \"$JKIT_ENV_FILE\" ] && echo \"\\$env:JAVA_HOME = '/x/jdk17'\" > \"$JKIT_ENV_FILE\"\nexit 0\n");
            fake.toFile().setExecutable(true);
        }
        String script = Hooks.powershell(tmp.resolve("root"), fake.toString()) + """
                jkit exec jdk@21 -- java -version
                jkit exec --no-project jdk@17 zulu -- mvn -- x
                $v = 'jdk@21'; jkit exec $v -- java
                jkit exec jdk@21 '--' java
                $s = 'exec', '--', 'x'; jkit @s
                jkit use jdk@17; "home: $env:JAVA_HOME"
                """;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        int ran = 0;
        for (String shell : new String[] {"powershell", "pwsh"}) {
            ProcessBuilder pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                    "Restricted", "-EncodedCommand", encoded).redirectErrorStream(true);
            withoutToolHomes(pb);
            Process p;
            try {
                p = pb.start();
            } catch (IOException notInstalled) {
                continue;
            }
            ran++;
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(out).as(shell).contains(
                    "argv: exec jdk@21 -- java -version",
                    "argv: exec --no-project jdk@17 zulu -- mvn -- x",
                    "argv: exec jdk@21 -- java",
                    "argv: exec -- x",
                    "home: " + (windows ? "C:\\x\\jdk17" : "/x/jdk17"));
            assertThat(out).as(shell).doesNotContain("-- --");
        }
        assumeTrue(ran > 0, "no PowerShell on this machine");
    }
}
