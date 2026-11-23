package dev.jkit.cli;

import dev.jkit.core.Config;
import dev.jkit.core.ConfigWriter;
import dev.jkit.core.ShellRc;
import dev.jkit.model.JkitException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "hook", description = {"Prints the shell hook, or adds it to your shell's startup file.",
        "The hook puts the global versions on PATH and lets jkit use change this shell.",
        "With auto_switch = true under [shell] in config.toml (or JKIT_AUTO_SWITCH=1), it also",
        "switches to a project's versions on cd, and says what it changed.",
        "",
        "  jkit hook --install                 add it for your login shell ($SHELL)",
        "  jkit hook --install --auto-switch   and switch on cd",
        "  eval \"$(jkit hook bash)\"           load it by hand (zsh alike; fish: jkit hook fish | source)",
        "  jkit hook --uninstall               take it out again"})
final class HookCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<shell>", completionCandidates = Candidates.Shells.class,
            description = "bash, zsh, fish or pwsh. With --install, defaults to your login shell ($SHELL).")
    String shell;

    @ArgGroup(exclusive = true)
    Action action = new Action();

    static final class Action {
        @Option(names = "--install", description = "Add the hook to the shell's startup file (~/.bashrc, ~/.zshrc, "
                + "~/.config/fish/conf.d/jkit.fish). Does nothing if it's already there.")
        boolean install;

        @Option(names = "--uninstall", description = "Take out what --install added.")
        boolean uninstall;
    }

    @Option(names = "--auto-switch", description = "With --install: also turn on switching to a project's "
            + "versions on cd (auto_switch = true under [shell] in config.toml).")
    boolean autoSwitch;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        // lenient: a typo in config.toml must not leave every new shell without the hook
        Context ctx = Context.lenient(System.getenv(), System.out);
        if (ctx.configError != null) {
            ctx.err.println("warning: " + ctx.configError);
        }
        if (autoSwitch && !action.install) {
            throw new JkitException("--auto-switch goes with --install",
                    "jkit hook --install --auto-switch (it skips the startup file if the hook is already there)");
        }
        if (action.install || action.uninstall) {
            String name = shell != null ? shell : loginShell(ctx);
            return action.install ? install(ctx, name, userHome(ctx), autoSwitch) : uninstall(ctx, name, userHome(ctx));
        }
        String name = shell != null ? shell : ctx.platform.isWindows() ? "pwsh" : "bash";
        ctx.out.print(ctx.os.hook(name, ctx.home.root()));
        if (ctx.config.autoSwitch()) {
            String auto = ctx.os.autoSwitchHook(name);
            if (auto != null) {
                ctx.out.print(auto);
            } else {
                ctx.err.println("warning: shell.auto_switch isn't supported in " + name
                        + " yet, so versions won't switch on cd; jkit sync switches this shell by hand");
            }
        }
        if (name.equals("bash") || name.equals("zsh")) {
            // tab completion comes with the hook, so source and tar.gz installs get it too
            ctx.out.print(CompletionCommand.script(spec.root().commandLine()));
        }
        return 0;
    }

    static Path userHome(Context ctx) {
        // an empty or relative HOME would put the rc file in the current folder
        Path home = dev.jkit.core.Fs.path(ctx.env.get("HOME"));
        return (home == null || !home.isAbsolute() ? Path.of(System.getProperty("user.home")) : home).normalize();
    }

    /** bash, zsh or fish from $SHELL; anything else has to be named. */
    static String loginShell(Context ctx) {
        String sh = ctx.env.get("SHELL");
        String name = sh == null || sh.isBlank() ? "" : Path.of(sh).getFileName().toString();
        if (List.of("bash", "zsh", "fish").contains(name)) {
            return name;
        }
        throw new JkitException("can't tell which shell to add the hook to"
                + (name.isEmpty() ? " ($SHELL isn't set)" : " ($SHELL is " + sh + ")"),
                "name it: jkit hook bash --install (or zsh, fish)");
    }

    /** Adds the block to the startup file and says exactly what changed. */
    static int install(Context ctx, String shell, Path userHome) throws Exception {
        return install(ctx, shell, userHome, false);
    }

    /**
     * Adds the block to the startup file unless it's there already, and with {@code autoSwitch} turns
     * on shell.auto_switch in config.toml; says exactly what changed.
     */
    static int install(Context ctx, String shell, Path userHome, boolean autoSwitch) throws Exception {
        Path file = ShellRc.file(shell, ctx.env, userHome);
        boolean exists = Files.exists(file);
        String text = exists ? Files.readString(file) : "";
        String shown = Changes.tilde(file.toString());
        int manual = ShellRc.manualLine(text);
        Changes report = new Changes(ctx);
        boolean added = false;
        if (ShellRc.hasBlock(text)) {
            ctx.out.println("the jkit hook is already in " + shown + "; nothing changed there");
        } else if (manual > 0) {
            ctx.out.println("line " + manual + " of " + shown + " already loads the jkit hook; nothing changed there");
        } else {
            createParent(ctx, file, report);
            Files.writeString(file, ShellRc.add(text, shell).text());
            report.file(file, !exists);
            added = true;
            ctx.out.println("added the jkit hook to " + shown + ":");
            for (String line : ShellRc.block(shell)) {
                ctx.out.println("    " + line);
            }
            if (Files.isSymbolicLink(file)) {
                report.note(shown + " is a link: the change went to " + Changes.tilde(file.toRealPath().toString()));
            }
            if (shell.equals("bash")) {
                bashLoginNote(userHome, report);
            }
        }
        boolean switched = autoSwitch && turnOnAutoSwitch(ctx, report);
        String reload = shell.equals("fish") ? "jkit hook fish | source" : "eval \"$(jkit hook " + shell + ")\"";
        if (added) {
            report.note("new " + shell + " shells load it; for this one: " + reload);
        } else if (switched) {
            report.note("new " + shell + " shells switch on cd; for this one: " + reload);
        }
        if (!autoSwitch && !ctx.config.autoSwitch()) {
            report.note("to also switch to a project's versions on cd: jkit hook " + shell + " --install --auto-switch");
        }
        report.print(ctx.out);
        return 0;
    }

    private static void createParent(Context ctx, Path file, Changes report) throws java.io.IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir)) {
            if (dir.equals(ctx.home.root())) {
                ctx.home.create();
            } else {
                Files.createDirectories(dir);
            }
            report.folder(dir, true);
        }
    }

    /** Sets auto_switch = true under [shell] in config.toml; true when that changed the file. */
    private static boolean turnOnAutoSwitch(Context ctx, Changes report) throws Exception {
        Path config = ctx.home.config();
        String shown = Changes.tilde(config.toString());
        boolean exists = Files.exists(config);
        ConfigWriter.Edit edit = ConfigWriter.set(config, exists ? dev.jkit.core.ProjectFile.text(config) : "",
                "shell", "auto_switch", "true");
        if (!edit.changed()) {
            ctx.out.println("auto_switch is already on in " + shown + "; nothing changed there");
        } else {
            createParent(ctx, config, report);
            Files.writeString(config, edit.text());
            report.file(config, !exists);
            ctx.out.println("turned on switching to a project's versions on cd: auto_switch = true under [shell] in "
                    + shown);
        }
        String env = ctx.env.get(Config.AUTO_SWITCH_ENV);
        if (env != null && !env.isBlank() && !Config.load(ctx.home, ctx.env).autoSwitch()) {
            report.note("warning: " + Config.AUTO_SWITCH_ENV + "=" + env + " is set and wins over config.toml; "
                    + "unset it for cd switching to work");
        }
        return edit.changed();
    }

    /** Login shells (macOS Terminal, ssh) read ~/.bash_profile, which may never load ~/.bashrc. */
    private static void bashLoginNote(Path userHome, Changes report) throws Exception {
        for (String name : List.of(".bash_profile", ".bash_login", ".profile")) {
            Path profile = userHome.resolve(name);
            if (Files.isRegularFile(profile)) {
                if (!Files.readString(profile).contains(".bashrc")) {
                    report.note("login shells read ~/" + name + ", which doesn't load ~/.bashrc; add to it:  "
                            + "[ -f ~/.bashrc ] && . ~/.bashrc");
                }
                return; // bash reads only the first of these that exists
            }
        }
    }

    /** Takes out the block --install added, and nothing else. */
    static int uninstall(Context ctx, String shell, Path userHome) throws Exception {
        Path file = ShellRc.file(shell, ctx.env, userHome);
        String shown = Changes.tilde(file.toString());
        String text = Files.exists(file) ? Files.readString(file) : "";
        ShellRc.Edit edit = ShellRc.remove(text);
        int manual = ShellRc.manualLine(edit.text());
        if (!edit.changed()) {
            ctx.out.println("jkit didn't add a hook to " + shown + "; nothing changed");
            if (manual > 0) {
                ctx.out.println("  line " + manual + " loads the jkit hook; jkit didn't add it, so remove it by hand");
            }
            return 0;
        }
        Changes report = new Changes(ctx);
        if (shell.equals("fish") && edit.text().isBlank()) {
            Files.delete(file); // the file was jkit's own
            report.fileDeleted(file);
        } else {
            Files.writeString(file, edit.text());
            report.file(file, false);
        }
        ctx.out.println("removed the jkit hook from " + shown);
        if (manual > 0) {
            report.note("line " + manual + " still loads the jkit hook; jkit didn't add it, so remove it by hand");
        }
        report.note("open shells keep the hook until they exit");
        report.print(ctx.out);
        return 0;
    }
}
