package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellRcTest {
    private static final Path HOME = Path.of("/home/u");

    @Test
    void startupFilePerShell() {
        assertThat(ShellRc.file("bash", Map.of(), HOME)).isEqualTo(HOME.resolve(".bashrc"));
        assertThat(ShellRc.file("zsh", Map.of(), HOME)).isEqualTo(HOME.resolve(".zshrc"));
        // absolute on every OS (C:\\etc\\zdot on Windows); a relative value falls back to HOME
        Path zdot = Path.of("/etc/zdot").toAbsolutePath();
        assertThat(ShellRc.file("zsh", Map.of("ZDOTDIR", zdot.toString()), HOME)).isEqualTo(zdot.resolve(".zshrc"));
        assertThat(ShellRc.file("zsh", Map.of("ZDOTDIR", "rel"), HOME)).isEqualTo(HOME.resolve(".zshrc"));
        assertThat(ShellRc.file("fish", Map.of(), HOME)).isEqualTo(HOME.resolve(".config/fish/conf.d/jkit.fish"));
        Path xdg = Path.of("/x").toAbsolutePath();
        assertThat(ShellRc.file("fish", Map.of("XDG_CONFIG_HOME", xdg.toString()), HOME))
                .isEqualTo(xdg.resolve("fish/conf.d/jkit.fish"));
        assertThatThrownBy(() -> ShellRc.file("pwsh", Map.of(), HOME)).isInstanceOf(JkitException.class)
                .hasMessageContaining("pwsh");
    }

    @Test
    void addsTheBlockAtTheEndOnceAndRemovesItAgain() {
        String rc = "export EDITOR=vim\nalias ll='ls -l'";
        ShellRc.Edit added = ShellRc.add(rc, "bash");
        assertThat(added.changed()).isTrue();
        assertThat(added.text()).startsWith(rc + "\n\n" + ShellRc.START + "\n")
                .contains("if command -v jkit >/dev/null 2>&1; then eval \"$(jkit hook bash)\"; fi\n")
                .endsWith(ShellRc.END + "\n");
        assertThat(ShellRc.add(added.text(), "bash").changed()).isFalse();

        ShellRc.Edit removed = ShellRc.remove(added.text());
        assertThat(removed.changed()).isTrue();
        assertThat(removed.text()).isEqualTo(rc + "\n");
        assertThat(ShellRc.remove(removed.text()).changed()).isFalse();
    }

    @Test
    void removesABlockInTheMiddleAndKeepsWhatFollows() {
        String rc = "a\n" + String.join("\n", ShellRc.block("zsh")) + "\nb\n";
        assertThat(ShellRc.remove(rc).text()).isEqualTo("a\nb\n");
    }

    @Test
    void anEmptyFileGetsJustTheBlockAndFishUsesItsOwnSyntax() {
        assertThat(ShellRc.add("", "fish").text())
                .isEqualTo(String.join("\n", ShellRc.block("fish")) + "\n")
                .contains("if status is-interactive; and type -q jkit; jkit hook fish | source; end");
        assertThat(ShellRc.remove(ShellRc.add("", "fish").text()).text()).isEmpty();
    }

    @Test
    void aHookLineAddedByHandIsLeftAlone() {
        String rc = "# eval \"$(jkit hook bash)\" (commented out)\nexport A=1\neval \"$(jkit hook bash)\"\n";
        assertThat(ShellRc.manualLine(rc)).isEqualTo(3);
        assertThat(ShellRc.add(rc, "bash").changed()).isFalse();
        assertThat(ShellRc.manualLine("# jkit hook\n")).isZero();
        assertThat(ShellRc.manualLine(ShellRc.add("", "bash").text())).isZero();
        assertThat(ShellRc.manualLine("alias jh='jkit hook bash'\n")).isZero(); // mentions it, doesn't load it
        assertThat(ShellRc.manualLine("jkit hook fish | source\n")).isEqualTo(1);
        assertThat(ShellRc.manualLine("source <(jkit hook zsh)\n")).isEqualTo(1);
        assertThat(ShellRc.manualLine("eval \"`jkit hook bash`\"\n")).isEqualTo(1);
        assertThat(ShellRc.manualLine("eval \"$(JKIT_AUTO_SWITCH=1 jkit hook bash)\"\n")).isEqualTo(1);
        assertThat(ShellRc.manualLine("[ -x \"$(command -v jkit)\" ] && eval \"$(~/bin/jkit hook bash)\"\n")).isEqualTo(1);
        assertThat(ShellRc.manualLine("jkit hook pwsh | Out-String | Invoke-Expression\n")).isEqualTo(1);
    }

    @Test
    void keepsWindowsLineEndings() {
        String added = ShellRc.add("a\r\n", "bash").text();
        assertThat(added).startsWith("a\r\n\r\n" + ShellRc.START + "\r\n").doesNotContain("\n" + ShellRc.END + "\n");
        assertThat(ShellRc.remove(added).text()).isEqualTo("a\r\n");
    }

    @org.junit.jupiter.api.io.TempDir
    Path tmp;

    /**
     * The block runs jkit at its own path, not a JDK's bin/jkit first on an inherited PATH; with that
     * path gone, the first jkit on PATH outside jkit's own folder.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void theBlockRunsJkitByPathAndSkipsToolFolders() throws Exception {
        Path root = tmp.resolve("o'b home/.jkit");
        Path tool = java.nio.file.Files.createDirectories(root.resolve("tools/jdk/21/bin"));
        Path other = java.nio.file.Files.createDirectories(tmp.resolve("usr bin"));
        Path self = java.nio.file.Files.createDirectories(tmp.resolve("self")).resolve("jkit");
        for (Path p : new Path[] {tool.resolve("jkit"), other.resolve("jkit"), self}) {
            java.nio.file.Files.writeString(p, "#!/bin/sh\necho 'echo " + p.getParent().getFileName() + "'\n");
            p.toFile().setExecutable(true);
        }
        String path = tool + ":" + other + ":/usr/bin:/bin";
        assertThat(runBash(String.join("\n", ShellRc.block("bash", self.toString(), root)), path)).isEqualTo("self");
        java.nio.file.Files.delete(self);
        assertThat(runBash(String.join("\n", ShellRc.block("bash", self.toString(), root)), path)).isEqualTo("usr bin");
        java.nio.file.Files.delete(other.resolve("jkit"));
        assertThat(runBash(String.join("\n", ShellRc.block("bash", self.toString(), root)), path)).isEmpty();
    }

    private String runBash(String script, String path) throws Exception {
        Path file = tmp.resolve("rc.sh");
        java.nio.file.Files.writeString(file, script + "\n");
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", file.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", path);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        assertThat(p.waitFor()).as(out).isZero();
        return out;
    }
}
