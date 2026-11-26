package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.core.ShellRc;
import dev.jkit.model.JkitException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** jkit hook --install and --uninstall: what they write, and that they say so. */
class HookInstallTest {
    @TempDir
    Path tmp;
    Path user;
    Map<String, String> env;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        user = Files.createDirectories(tmp.resolve("user"));
        env = new HashMap<>(Map.of("HOME", user.toString(), "JKIT_HOME", user.resolve(".jkit").toString(),
                "SHELL", "/bin/bash"));
    }

    private Context ctx() {
        out.reset();
        return Context.forEnv(env, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aStartupFileThatIsNotUtf8KeepsItsBytes() throws Exception {
        Path rc = user.resolve(".bashrc");
        byte[] latin1 = "# caf\u00e9\nexport A=1\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(rc, latin1);
        HookCommand.install(ctx(), "bash", user);
        byte[] after = Files.readAllBytes(rc);
        assertThat(Arrays.copyOf(after, latin1.length)).isEqualTo(latin1);
        HookCommand.uninstall(ctx(), "bash", user);
        assertThat(Files.readAllBytes(rc)).isEqualTo(latin1);
    }

    /** A dotfile manager's ~/.bashrc link stays a link, keeps its mode, and the change is said to go to its file. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkedStartupFileStaysALinkWithItsMode() throws Exception {
        Path real = Files.writeString(Files.createDirectories(user.resolve("dotfiles")).resolve("bashrc"), "export A=1\r\n");
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-------"));
        Path rc = Files.createSymbolicLink(user.resolve(".bashrc"), Path.of("dotfiles/bashrc"));
        HookCommand.install(ctx(), "bash", user);
        assertThat(Files.isSymbolicLink(rc)).isTrue();
        assertThat(Files.readString(real)).startsWith("export A=1\r\n\n" + ShellRc.START + "\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(real)))
                .isEqualTo("rw-------");
        assertThat(output()).contains("is a link: the change went to");
        HookCommand.uninstall(ctx(), "bash", user);
        assertThat(Files.isSymbolicLink(rc)).isTrue();
        assertThat(Files.readString(real)).isEqualTo("export A=1\r\n");
        assertThat(output()).contains("is a link: the change went to");
    }

    @Test
    void aBlockWithoutItsEndMarkerIsLeftAloneWithOneClearMessage() throws Exception {
        String text = "export A=1\n" + ShellRc.START + "\neval \"$(jkit hook bash)\"\n";
        Path rc = Files.writeString(user.resolve(".bashrc"), text);
        for (boolean install : new boolean[] {true, false}) {
            assertThatThrownBy(() -> {
                if (install) {
                    HookCommand.install(ctx(), "bash", user);
                } else {
                    HookCommand.uninstall(ctx(), "bash", user);
                }
            }).isInstanceOf(JkitException.class)
                    .hasMessageEndingWith("line 2 of " + Changes.tilde(rc.toString()) + " starts jkit's block ("
                            + ShellRc.START + ") but no " + ShellRc.END + " line ends it; nothing changed")
                    .satisfies(e -> assertThat(((JkitException) e).hints().getFirst())
                            .endsWith("then run jkit hook bash " + (install ? "--install" : "--uninstall") + " again"));
            assertThat(Files.readString(rc)).isEqualTo(text);
        }
    }

    @Test
    void installAppendsToBashrcAndSaysWhatChanged() throws Exception {
        Path rc = user.resolve(".bashrc");
        Files.writeString(rc, "export EDITOR=vim\n");
        Context ctx = ctx();
        HookCommand.install(ctx, HookCommand.loginShell(ctx), user);
        assertThat(Files.readString(rc)).startsWith("export EDITOR=vim\n\n" + ShellRc.START)
                .contains("eval \"$(jkit hook bash)\"");
        assertThat(output()).contains("added the jkit hook to").contains("    " + ShellRc.START)
                .contains("file   " + rc + " updated")
                .contains("new bash shells load it; for this one: eval \"$(jkit hook bash)\"")
                .contains("to also switch to a project's versions on cd: jkit hook bash --install --auto-switch");

        HookCommand.install(ctx(), "bash", user);
        assertThat(output()).contains("already in").contains("nothing changed there");
        assertThat(Files.readString(rc).split(ShellRc.START, -1)).hasSize(2);
    }

    @Test
    void installLeavesAHandWrittenHookAlone() throws Exception {
        Path rc = user.resolve(".zshrc");
        Files.writeString(rc, "eval \"$(jkit hook zsh)\"\n");
        HookCommand.install(ctx(), "zsh", user);
        assertThat(output()).contains("line 1 of").contains("already loads the jkit hook; nothing changed");
        assertThat(Files.readString(rc)).isEqualTo("eval \"$(jkit hook zsh)\"\n");
    }

    @Test
    void fishGetsAFileOfItsOwnWhichUninstallDeletes() throws Exception {
        HookCommand.install(ctx(), "fish", user);
        Path file = user.resolve(".config/fish/conf.d/jkit.fish");
        assertThat(file).exists();
        assertThat(output()).contains("folder " + file.getParent() + " created").contains("file   " + file + " created")
                .contains("for this one: jkit hook fish | source");

        HookCommand.uninstall(ctx(), "fish", user);
        assertThat(file).doesNotExist();
        assertThat(output()).contains("removed the jkit hook").contains("file   " + file + " deleted");
    }

    @Test
    void uninstallTakesOutOnlyTheBlock() throws Exception {
        Path rc = user.resolve(".bashrc");
        Files.writeString(rc, "export A=1\n");
        HookCommand.install(ctx(), "bash", user);
        HookCommand.uninstall(ctx(), "bash", user);
        assertThat(Files.readString(rc)).isEqualTo("export A=1\n");
        assertThat(output()).contains("file   " + rc + " updated").contains("open shells keep the hook");

        HookCommand.uninstall(ctx(), "bash", user);
        assertThat(output()).contains("jkit didn't add a hook to").contains("nothing changed");
    }

    @Test
    void bashWarnsWhenTheLoginProfileSkipsBashrc() throws Exception {
        Files.writeString(user.resolve(".bash_profile"), "export PATH=$HOME/bin:$PATH\n");
        HookCommand.install(ctx(), "bash", user);
        assertThat(output()).contains("login shells read ~/.bash_profile, which doesn't load ~/.bashrc");

        Files.writeString(user.resolve(".bash_profile"), "[ -f ~/.bashrc ] && . ~/.bashrc\n");
        Files.delete(user.resolve(".bashrc"));
        HookCommand.install(ctx(), "bash", user);
        assertThat(output()).doesNotContain("login shells");
    }

    @Test
    void whatInstallCreatesUninstallTakesAwayAndBothSaySo() throws Exception {
        // fish: every new folder is reported, not just conf.d
        env.put("SHELL", "/usr/bin/fish");
        HookCommand.install(ctx(), "fish", user);
        assertThat(output()).contains("folder " + user.resolve(".config") + " created")
                .contains("folder " + user.resolve(".config/fish") + " created")
                .contains("folder " + user.resolve(".config/fish/conf.d") + " created");
        // bash: a ~/.bashrc that --install created goes again
        Path rc = user.resolve(".bashrc");
        HookCommand.install(ctx(), "bash", user);
        assertThat(rc).exists();
        HookCommand.uninstall(ctx(), "bash", user);
        assertThat(rc).doesNotExist();
        assertThat(output()).contains("file   " + rc + " deleted");
        // but not a blank startup file the user made (an empty ~/.zshrc keeps zsh's new-user wizard away)
        Path zshrc = Files.writeString(user.resolve(".zshrc"), "\n\n");
        HookCommand.install(ctx(), "zsh", user);
        assertThat(Files.readString(zshrc)).doesNotContain(ShellRc.CREATED);
        HookCommand.uninstall(ctx(), "zsh", user);
        assertThat(zshrc).exists();
        assertThat(Files.readString(zshrc)).doesNotContain(ShellRc.START);
        // without $SHELL, the hint repeats what was asked
        env.remove("SHELL");
        assertThatThrownBy(() -> HookCommand.loginShell(ctx(), "--uninstall"))
                .satisfies(e -> assertThat(((JkitException) e).hints()).contains("name it: jkit hook bash --uninstall (or zsh, fish)"))
                .hasMessageContaining("to take the hook out of");
    }

    @Test
    void anUnknownLoginShellMustBeNamed() {
        env.put("SHELL", "/bin/tcsh");
        assertThatThrownBy(() -> HookCommand.loginShell(ctx())).isInstanceOf(JkitException.class)
                .hasMessageContaining("$SHELL is /bin/tcsh");
    }

    @Test
    void autoSwitchWritesConfigTomlAndSaysSo() throws Exception {
        Path config = user.resolve(".jkit/config.toml");
        HookCommand.install(ctx(), "bash", user, true);
        assertThat(Files.readString(config)).isEqualTo("[shell]\nauto_switch = true\n");
        assertThat(output()).contains("added the jkit hook").contains("turned on switching to a project's versions on cd")
                .contains("folder " + config.getParent() + " created").contains("file   " + config + " created")
                .doesNotContain("--auto-switch");

        // hook already there: only config.toml changes, and the note says how to get it in this shell
        Files.writeString(config, "[shell]\nauto_switch = false\n");
        HookCommand.install(ctx(), "bash", user, true);
        assertThat(Files.readString(config)).isEqualTo("[shell]\nauto_switch = true\n");
        assertThat(output()).contains("already in").contains("file   " + config + " updated")
                .contains("new bash shells switch on cd; for this one: eval \"$(jkit hook bash)\"");

        HookCommand.install(ctx(), "bash", user, true);
        assertThat(output()).contains("auto_switch is already on").doesNotContain("file   ");
    }

    @Test
    void autoSwitchWarnsWhenTheEnvironmentTurnsItOff() throws Exception {
        env.put("JKIT_AUTO_SWITCH", "0");
        HookCommand.install(ctx(), "bash", user, true);
        assertThat(output()).contains("warning: JKIT_AUTO_SWITCH=0 is set and wins over config.toml");
    }
}
