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
        "With auto_switch = true under [shell] in config.toml (or JKIT_AUTO_SWITCH=1),",
        "it also switches to a project's versions on cd, and says what it changed.",
        "",
        "  jkit hook --install                 add it for your login shell ($SHELL)",
        "  jkit hook --install --auto-switch   and switch on cd",
        "  eval \"$(jkit hook bash)\"           load it by hand (zsh alike)",
        "  jkit hook fish | source             load it by hand in fish",
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
        Context ctx = Context.lenient(dev.jkit.model.Envs.system(), System.out);
        if (ctx.configError != null) {
            ctx.err.println("warning: " + ctx.configError);
        }
        if (autoSwitch && !action.install) {
            throw new JkitException("--auto-switch goes with --install",
                    "jkit hook --install --auto-switch (it skips the startup file if the hook is already there)");
        }
        if (action.install || action.uninstall) {
            String name = shell != null ? shell : loginShell(ctx, action.install ? "--install" : "--uninstall");
            return action.install ? install(ctx, name, userHome(ctx), autoSwitch) : uninstall(ctx, name, userHome(ctx));
        }
        String name = shell != null ? shell : ctx.platform.isWindows() ? "pwsh" : "bash";
        String self = self(ctx);
        ctx.out.print(ctx.os.hook(name, ctx.home.root(), self));
        if (ctx.config.autoSwitch()) {
            String auto = ctx.os.autoSwitchHook(name, self);
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
        if (home == null || !home.isAbsolute()) {
            home = dev.jkit.core.Fs.path(System.getProperty("user.home"));
        }
        if (home == null || !home.isAbsolute()) {
            throw new dev.jkit.model.JkitException("can't tell your home folder, so not which startup file to change",
                    "set HOME to an absolute path");
        }
        return home.normalize();
    }

    /** bash, zsh or fish from $SHELL; anything else has to be named. */
    static String loginShell(Context ctx) {
        return loginShell(ctx, "--install");
    }

    /** {@link #loginShell(Context)}, with a hint for {@code flag}: --install or --uninstall. */
    static String loginShell(Context ctx, String flag) {
        String sh = ctx.env.get("SHELL");
        String name = sh == null || sh.isBlank() ? "" : Path.of(sh).getFileName().toString();
        if (List.of("bash", "zsh", "fish").contains(name)) {
            return name;
        }
        throw new JkitException("can't tell which shell " + (flag.equals("--install") ? "to add the hook to" : "to take the hook out of")
                + (name.isEmpty() ? " ($SHELL isn't set)" : " ($SHELL is " + sh + ")"),
                "name it: jkit hook bash " + flag + " (or zsh, fish)");
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
        java.nio.charset.Charset charset = charsetOf(file);
        String text = exists ? Files.readString(file, charset) : "";
        String shown = Changes.tilde(file.toString());
        int manual = ShellRc.manualLine(text);
        String self = self(ctx);
        Changes report = new Changes(ctx);
        boolean added = false;
        if (ShellRc.hasBlock(text)) {
            ctx.out.println("the jkit hook is already in " + shown + "; nothing changed there");
        } else if (manual > 0) {
            ctx.out.println("line " + manual + " of " + shown + " already loads the jkit hook; nothing changed there");
        } else {
            createParent(ctx, file, report);
            String written = ShellRc.add(text, shell, self, ctx.home.root(), !exists).text();
            // all at once, through a dotfile manager's link; a new file is 644 whatever the umask
            dev.jkit.core.AtomicFile.write(file, written, charset, true);
            report.file(file, !exists);
            added = true;
            ctx.out.println("added the jkit hook to " + shown + ":");
            // exactly the lines written, from the marker on
            List<String> lines = written.lines().toList();
            for (String line : lines.subList(lines.indexOf(ShellRc.START), lines.size())) {
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
        String jkit = self == null ? "jkit" : dev.jkit.model.ShellQuote.quote(shell, self);
        String reload = shell.equals("fish") ? jkit + " hook fish | source" : "eval \"$(" + jkit + " hook " + shell + ")\"";
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

    static void createParent(Context ctx, Path file, Changes report) throws java.io.IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir)) {
            // every folder that is new, outermost first: fish's ~/.config, ~/.config/fish and conf.d
            List<Path> missing = new java.util.ArrayList<>();
            for (Path d = dir; d != null && !Files.exists(d); d = d.getParent()) {
                missing.addFirst(d);
            }
            if (dir.equals(ctx.home.root())) {
                ctx.home.create();
            } else {
                dev.jkit.model.Perms.mkdirs(dir); // 755 whatever the umask: other users mustn't edit startup files
            }
            for (Path d : missing) {
                report.folder(d, true);
            }
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
            dev.jkit.core.AtomicFile.write(config, edit.text(), java.nio.charset.StandardCharsets.UTF_8, true);
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
                if (!Files.readString(profile, charsetOf(profile)).contains(".bashrc")) {
                    report.note("login shells read ~/" + name + ", which doesn't load ~/.bashrc; add to it:  "
                            + "[ -f ~/.bashrc ] && . ~/.bashrc");
                }
                return; // bash reads only the first of these that exists
            }
        }
    }

    /**
     * UTF-8, or Latin-1 for a startup file that isn't valid UTF-8 (an old comment with an accent):
     * read and written back in the same charset, so every byte jkit doesn't change stays as it was.
     */
    static java.nio.charset.Charset charsetOf(Path file) throws java.io.IOException {
        if (!Files.isRegularFile(file)) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)));
            return java.nio.charset.StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException e) {
            return java.nio.charset.StandardCharsets.ISO_8859_1;
        }
    }

    /** Takes out the block --install added, and nothing else. */
    static int uninstall(Context ctx, String shell, Path userHome) throws Exception {
        Path file = ShellRc.file(shell, ctx.env, userHome);
        String shown = Changes.tilde(file.toString());
        java.nio.charset.Charset charset = charsetOf(file);
        String text = Files.exists(file) ? Files.readString(file, charset) : "";
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
        // fish's conf.d file is jkit's own; a ~/.bashrc or ~/.zshrc only when --install created it
        // (an empty ~/.zshrc a user made keeps zsh's first-run wizard away)
        boolean jkitsFile = shell.equals("fish") || ShellRc.createdByJkit(text);
        if (edit.text().isBlank() && jkitsFile && !Files.isSymbolicLink(file)) {
            Files.delete(file);
            report.fileDeleted(file);
        } else {
            dev.jkit.core.AtomicFile.write(file, edit.text(), charset, true);
            report.file(file, false);
            if (Files.isSymbolicLink(file)) {
                report.note(shown + " is a link: the change went to " + Changes.tilde(file.toRealPath().toString()));
            }
        }
        ctx.out.println("removed the jkit hook from " + shown);
        if (manual > 0) {
            report.note("line " + manual + " still loads the jkit hook; jkit didn't add it, so remove it by hand");
        }
        report.note("open shells keep the hook until they exit");
        report.print(ctx.out);
        return 0;
    }

    /**
     * This binary's absolute path, for the hook to call: a tool's bin folder comes first on PATH
     * once it is active, and a {@code jkit} in it must not be what runs on every cd. A PATH entry that
     * leads to this binary is preferred (Homebrew's bin/jkit link outlives an upgrade; the Cellar path
     * doesn't). Null when unknown, or when running on a JVM (tests): the hook then looks jkit up on PATH.
     */
    static String self(Context ctx) {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
            return null;
        }
        try {
            java.util.Optional<String> command = ProcessHandle.current().info().command();
            if (command.isEmpty()) {
                return null;
            }
            Path real = Path.of(command.get()).toRealPath();
            String file = real.getFileName().toString();
            Path tools = ctx.home.root().toAbsolutePath().normalize();
            for (String dir : ctx.env.getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                try {
                    Path entry = Path.of(dir);
                    if (!entry.isAbsolute() || entry.normalize().startsWith(tools)) {
                        continue;
                    }
                    Path candidate = entry.resolve(file);
                    if (java.nio.file.Files.exists(candidate) && java.nio.file.Files.isSameFile(candidate, real)) {
                        return candidate.toString();
                    }
                } catch (java.nio.file.InvalidPathException | java.io.IOException e) {
                    // not usable: the next entry
                }
            }
            return real.toString();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
