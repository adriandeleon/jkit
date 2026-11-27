package dev.jkit.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
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

    /** A JAVA_HOME of the user's own wins over the global JDK, so java on PATH is that one too. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookPutsTheUsersOwnJavaHomeOnPathInsteadOfTheGlobalOne() throws Exception {
        Path root = tmp.resolve("home/.jkit");
        Files.createDirectories(root.resolve("global"));
        Files.createSymbolicLink(root.resolve("global/jdk"), Files.createDirectories(tmp.resolve("jdk")));
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, "set -u\n" + Hooks.posix("bash", root) + "\nprintf '%s\\n' \"$JAVA_HOME\" \"$PATH\"\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString())
                .redirectErrorStream(true);
        withoutToolHomes(pb);
        pb.environment().put("JAVA_HOME", mine.toString());
        pb.environment().put("PATH", "/usr/bin:/bin");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.lines().toList()).as(out).containsExactly(mine.toString(),
                mine.resolve("bin") + ":" + root.resolve("global/maven/bin") + ":" + root.resolve("global/gradle/bin")
                        + ":" + root.resolve("global/sbt/bin") + ":/usr/bin:/bin");
    }

    /** A JAVA_HOME written with a trailing slash puts its bin on PATH as jkit writes it: no //. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookDropsATrailingSlashFromTheUsersJavaHome() throws Exception {
        Path root = tmp.resolve("home/.jkit");
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, "set -u\n" + Hooks.posix("bash", root) + "\nprintf '%s\\n' \"$PATH\"\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString())
                .redirectErrorStream(true);
        withoutToolHomes(pb);
        pb.environment().put("JAVA_HOME", mine + "/");
        pb.environment().put("PATH", "/usr/bin:/bin");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out).as(out).startsWith(mine.resolve("bin") + ":").doesNotContain("//");
    }

    /** The same in PowerShell, where the hook trims the slash or backslash. */
    @Test
    void powershellHookDropsATrailingSlashFromTheUsersJavaHome() throws Exception {
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        // PowerShell can write a progress record ("Preparing modules for first use") on its first
        // run: it is turned off, and only stdout is read, so PATH is the whole output
        String script = "$ProgressPreference = 'SilentlyContinue'\n" + Hooks.powershell(tmp.resolve("root"), null)
                + "\n$env:PATH\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        int ran = 0;
        for (String shell : new String[] {"powershell", "pwsh"}) {
            Path err = tmp.resolve(shell + ".err");
            ProcessBuilder pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectError(err.toFile());
            withoutToolHomes(pb);
            pb.environment().put("JAVA_HOME", mine + File.separator);
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
            assertThat(out).as(() -> shell + ", stderr: " + read(err)).startsWith(mine.resolve("bin") + File.pathSeparator);
        }
        assumeTrue(ran > 0, "no PowerShell on this machine");
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return e.toString();
        }
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
        assertThat(Hooks.autoSwitch("bash")).contains("eval \"$(__jkit_run hook-env bash)\"");
        assertThat(Hooks.fish(Path.of("/home/u/.jkit"), self)).contains("set -g __jkit_bin '/opt/jk it\\'s/bin/jkit'")
                .contains("set -lx JKIT_ENV_FILE $envfile");
        assertThat(Hooks.powershell(Path.of("/home/u/.jkit"), self)).contains("$global:__jkit_bin = '/opt/jk it''s/bin/jkit'");
    }

    /**
     * A global bin that PATH has already, but behind the system's (macOS's path_helper in a nested
     * login shell puts /usr/bin first): the hook moves it to the front, written once. Empty
     * entries (the current folder) and the rest keep their order.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookMovesAGlobalBinThatIsFurtherBackToTheFront() throws Exception {
        Path root = tmp.resolve("home/.jkit");
        Files.createDirectories(root.resolve("global"));
        Files.createSymbolicLink(root.resolve("global/jdk"), Files.createDirectories(tmp.resolve("jdk")));
        String g = root.resolve("global").toString();
        String globals = g + "/jdk/bin:" + g + "/maven/bin:" + g + "/gradle/bin:" + g + "/sbt/bin";
        assertThat(bashPath(root, "/sys:/usr/bin:" + g + "/jdk/bin", null)).isEqualTo(globals + ":/sys:/usr/bin");
        // with a trailing /, twice, and around empty entries
        assertThat(bashPath(root, "/sys::" + g + "/jdk/bin/:" + g + "/jdk/bin:/usr/bin:", null))
                .isEqualTo(globals + ":/sys::/usr/bin:");
        assertThat(bashPath(root, g + "/jdk/bin", null)).isEqualTo(globals);
        assertThat(bashPath(root, globals + ":/usr/bin", null)).isEqualTo(globals + ":/usr/bin");
        // an empty PATH is one empty entry, the current folder, as before
        assertThat(bashPath(root, "", null)).isEqualTo(globals + ":");
        // a JAVA_HOME of the user's own: its bin is the one moved to the front
        Path mine = Files.createDirectories(tmp.resolve("my jdk [x]/bin")).getParent();
        assertThat(bashPath(root, "/usr/bin:" + mine + "/bin", mine.toString()))
                .isEqualTo(mine + "/bin:" + g + "/maven/bin:" + g + "/gradle/bin:" + g + "/sbt/bin:/usr/bin");
    }

    /** PATH after the bash hook loads with {@code path} and, if not null, JAVA_HOME. */
    private String bashPath(Path root, String path, String javaHome) throws Exception {
        Path script = tmp.resolve("hook-path.sh");
        Files.writeString(script, "set -u\n" + Hooks.posix("bash", root) + "\nprintf '%s\\n' \"$PATH\"\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString())
                .redirectErrorStream(true);
        withoutToolHomes(pb);
        pb.environment().put("PATH", path);
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome);
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        return out.stripTrailing();
    }

    /**
     * The same in PowerShell, where jkit env --install puts the global bin on the user PATH, which
     * Windows puts after the system one: java would be the system's while JAVA_HOME is jkit's.
     */
    @Test
    void powershellHookMovesAGlobalBinThatIsFurtherBackToTheFront() throws Exception {
        Path root = tmp.resolve("root");
        Files.createDirectories(root.resolve("global/jdk/bin"));
        String sep = File.pathSeparator;
        Path g = root.resolve("global");
        String jdkBin = g.resolve("jdk").resolve("bin").toString();
        String sys = tmp.resolve("sys").toString();
        String usr = tmp.resolve("usr").toString();
        // the PowerShell itself must still start: the machine's PATH comes after
        String machine = System.getenv().getOrDefault("PATH", "");
        // on Windows the same folder in another case and with a trailing backslash goes too
        String later = File.separatorChar == '\\' ? jdkBin.toUpperCase(Locale.ROOT) + "\\" : jdkBin + "/";
        String out = powershellPath(root, String.join(sep, sys, usr, later, jdkBin, machine));
        String globals = String.join(sep, jdkBin, g.resolve("maven").resolve("bin").toString(),
                g.resolve("gradle").resolve("bin").toString(), g.resolve("sbt").resolve("bin").toString());
        assertThat(out).startsWith(globals + sep);
        assertThat(out.substring(globals.length() + 1)).isEqualTo(String.join(sep, sys, usr, machine));
    }

    /** PATH after the PowerShell hook loads with {@code path}, the same from each PowerShell on this machine. */
    private String powershellPath(Path root, String path) throws Exception {
        String script = "$ProgressPreference = 'SilentlyContinue'\n" + Hooks.powershell(root, null) + "\n$env:PATH\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String last = null;
        for (String shell : new String[] {"powershell", "pwsh"}) {
            Path err = tmp.resolve(shell + ".err");
            ProcessBuilder pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectError(err.toFile());
            withoutToolHomes(pb);
            pb.environment().put("PATH", path);
            Process p;
            try {
                p = pb.start();
            } catch (IOException notInstalled) {
                continue;
            }
            p.getOutputStream().close();
            String raw = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            // pwsh 7 puts its own folder first on PATH as it starts and Windows PowerShell doesn't:
            // ahead of the test's first entry, only the hook's folders under root count
            String first = path.split(File.pathSeparator, -1)[0];
            java.util.List<String> entries = java.util.Arrays.asList(raw.split(File.pathSeparator, -1));
            int at = entries.indexOf(first);
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (at < 0 || i >= at || entries.get(i).startsWith(root.toString())) {
                    kept.add(entries.get(i));
                }
            }
            String out = String.join(File.pathSeparator, kept);
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            String before = last;
            assertThat(before == null || before.equals(out)).as(() -> shell + ": " + out + ", stderr: " + read(err))
                    .isTrue();
            last = out;
        }
        assumeTrue(last != null, "no PowerShell on this machine");
        return last;
    }

    /**
     * PowerShell decodes a program's output in the OEM code page and Java writes the ANSI one, so
     * the hook is ASCII only; a path with an accent, a typographic quote and a character past
     * U+FFFF still comes out exactly as it is.
     */
    @Test
    void powershellHookIsAsciiAndKeepsEveryCharacterOfItsPaths() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("Jos\u00E9 \uD83D\uDE00 o'b\u2019").resolve(".jkit"));
        Files.createDirectories(root.resolve("global/jdk/bin"));
        String self = root.resolveSibling("bin").resolve("jkit\u00E9").toString();
        String hook = Hooks.powershell(root, self);
        assertThat(hook.chars().filter(c -> c >= 0x80).count()).as(hook).isZero();
        String script = "$ProgressPreference = 'SilentlyContinue'\n" + hook
                + "\n[Console]::OutputEncoding = [Text.Encoding]::UTF8\n"
                + "[Console]::Out.WriteLine($global:__jkit_root)\n[Console]::Out.WriteLine($global:__jkit_bin)\n"
                + "[Console]::Out.WriteLine($env:JAVA_HOME)\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        int ran = 0;
        for (String shell : new String[] {"powershell", "pwsh"}) {
            Path err = tmp.resolve(shell + ".err");
            ProcessBuilder pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectError(err.toFile());
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
            assertThat(out.lines().map(l -> l.replace("\uFEFF", "")).toList()).as(() -> shell + ", stderr: " + read(err))
                    .containsExactly(root.toString(), self, root.resolve("global").resolve("jdk").toString());
        }
        assumeTrue(ran > 0, "no PowerShell on this machine");
    }

    /**
     * The fallback when the saved jkit is gone skips relative PATH entries: with . or bin on PATH,
     * a cd into a repository with a ./jkit or bin/jkit would run that.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookFallbackSkipsRelativePathEntries() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo/bin")).getParent();
        Path real = Files.createDirectories(tmp.resolve("real"));
        for (Path p : new Path[] {repo.resolve("bin/jkit"), repo.resolve("jkit"), real.resolve("jkit")}) {
            Files.writeString(p, "#!/bin/sh\necho \"" + p + "\"\n");
            p.toFile().setExecutable(true);
        }
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, Hooks.posix("bash", tmp.resolve("home"), tmp.resolve("gone/jkit").toString())
                + "cd '" + repo + "'\njkit x\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString()).redirectErrorStream(true);
        withoutToolHomes(pb);
        pb.environment().put("PATH", "bin:.::" + real + ":/usr/bin:/bin");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.lines().toList()).as(out).containsExactly(real.resolve("jkit").toString());
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

    /**
     * Ctrl-C at the prompt makes bash leave the jkit function before its rm; jkit removes the file
     * itself then, but one it couldn't (stopped before its handler was in place) is removed by the
     * next run. The shell's own Ctrl-C handling, which stops the rest of the line, is left alone.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bashHookRemovesAFileAnInterruptedRunLeft() throws Exception {
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path fake = tmp.resolve("fakejkit");
        Files.writeString(fake, "#!/bin/sh\nexit 0\n");
        fake.toFile().setExecutable(true);
        Path script = tmp.resolve("hook.sh");
        Files.writeString(script, Hooks.posix("bash", tmp.resolve("root"), fake.toString())
                + "__jkit_last_env=$(mktemp \"$TMPDIR/jkit-env.XXXXXX\")\nls -A \"$TMPDIR\" | wc -l\n"
                + "jkit status\necho \"rc=$?\"\nls -A \"$TMPDIR\" | wc -l\n");
        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", script.toString()).redirectErrorStream(true);
        withoutToolHomes(pb);
        pb.environment().put("TMPDIR", temp.toString());
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.lines().map(String::strip).toList()).as(out).containsExactly("1", "rc=0", "0");
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
        assertThat(ps).contains("'C:\\Users\\O''Brien\\.jkit").contains("$env:PATH = (@($bin) + $rest) -join $sep")
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
