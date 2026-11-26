package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Run by the shell hook when the directory changes, with {@code shell.auto_switch} on: prints shell
 * code that applies the project found from here (or takes the last one away), and tells the user
 * on stderr what it changed. Prints nothing when the project is the one already applied. Never
 * uses the network and never fails: a cd must always work.
 */
@Command(name = "hook-env", hidden = true,
        description = "Prints the shell code the jkit hook runs on cd (shell.auto_switch).")
final class HookEnvCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<shell>", description = "bash, zsh or fish.")
    String shell;

    @Override
    public Integer call() {
        try {
            Map<String, String> env = dev.jkit.model.Envs.system();
            Path cwd = Path.of("").toAbsolutePath();
            // this runs on every cd: the usual answer, nothing to do, comes before jkit's home,
            // config and store are read
            if (nothingToDo(env, ProjectFile.locate(cwd, env))) {
                return 0;
            }
            Context ctx = Context.lenient(env, System.out);
            String name = shell != null ? shell : ctx.env.getOrDefault("JKIT_SHELL", "bash");
            ctx.out.print(run(ctx, cwd, name, ctx.err));
        } catch (Exception | Error e) {
            System.err.println("jkit: couldn't check for a project file here: " + dev.jkit.model.Visible.of(e.getMessage()));
        }
        return 0;
    }

    /**
     * Whether a cd changes nothing, from the environment and the project files found alone:
     * switching was turned off in this shell ({@code JKIT_AUTO_SWITCH=0} exported after the hook
     * loaded; config.toml only counts for new shells), there is no project and none was applied,
     * or it is the project already applied, unchanged.
     */
    static boolean nothingToDo(Map<String, String> env, List<Path> files) {
        String auto = env.get(dev.jkit.core.Config.AUTO_SWITCH_ENV);
        if (auto != null && !auto.isBlank() && Boolean.FALSE.equals(dev.jkit.core.Config.parseSwitch(auto))) {
            return true;
        }
        String key = files.isEmpty() ? null : ProjectFile.key(files);
        String applied = env.get(ShellEnv.PROJECT);
        if (key == null && applied == null && !hasProjectState(env)) {
            return true; // the common case outside projects: no files, nothing applied
        }
        return key != null && key.equals(applied); // still the same project, unchanged
    }

    /** The shell code to evaluate ("" when nothing changes), telling {@code err} what it changes. */
    static String run(Context ctx, Path cwd, String shell, PrintStream err) throws Exception {
        List<Path> files = ProjectFile.locate(cwd, ctx.env);
        if (nothingToDo(ctx.env, files)) {
            return "";
        }
        String key = files.isEmpty() ? null : ProjectFile.key(files);
        String applied = ctx.env.get(ShellEnv.PROJECT);
        // the same project, unchanged, but a pin wasn't installed last time: look again, quietly
        boolean recheck = key != null && (key + ProjectShell.MISSING).equals(applied);

        ProjectShell.Plan plan;
        try {
            plan = ProjectShell.plan(ctx, cwd);
        } catch (JkitException e) {
            // a broken project file: say so once (until it changes), and leave the versions alone
            err.println("jkit: " + dev.jkit.model.Visible.of(e.getMessage()) + "; this shell's versions are unchanged");
            Map<String, String> remember = new LinkedHashMap<>();
            remember.put(ShellEnv.PROJECT, key);
            new Changes(ctx).env(remember).print(err); // remembered so the error shows once, and said
            return ShellEnv.format(remember, shell);
        }
        Map<String, String> changes = plan.changes();
        if (recheck && !changesMoreThanTheKey(ctx.env, changes)) {
            return ShellEnv.format(changes, shell);
        }
        if (plan.project().isEmpty() && !hasProjectState(ctx.env)) {
            // leaving a project that never applied anything (nothing installed, or a broken file):
            // the versions stay, and only jkit's note of the project goes, which is still said
            String left = appliedDir(applied);
            err.println("jkit: left the project" + (left == null ? "" : " in " + Changes.tilde(left))
                    + "; it had changed no versions");
            new Changes(ctx).env(changes).print(err);
            return ShellEnv.format(changes, shell);
        }
        err.println("jkit: " + headline(plan, applied, ctx.env));
        Changes report = new Changes(ctx);
        report.env(changes);
        plan.notes().forEach(report::note);
        report.print(err);
        return ShellEnv.format(changes, shell);
    }

    private static boolean hasProjectState(Map<String, String> env) {
        for (Tool t : Tool.values()) {
            if (env.containsKey(ShellEnv.projectVariable(t))) {
                return true;
            }
        }
        return false;
    }

    private static boolean changesMoreThanTheKey(Map<String, String> env, Map<String, String> changes) {
        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (!e.getKey().equals(ShellEnv.PROJECT) && !java.util.Objects.equals(env.get(e.getKey()), e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** {@code using ~/src/app/jkit.toml: jdk 21.0.9+10 temurin, maven 3.9.11}, or what leaving means. */
    static String headline(ProjectShell.Plan plan, String applied) {
        return headline(plan, applied, Map.of());
    }

    /**
     * {@link #headline(ProjectShell.Plan, String)} for a shell with {@code env}. Leaving a project,
     * each tool it applied goes back to the global version, unless this shell chose its own with
     * jkit use: that one stays, and saying "back to global" would be wrong for it.
     */
    static String headline(ProjectShell.Plan plan, String applied, Map<String, String> env) {
        if (plan.project().isEmpty()) {
            String left = appliedDir(applied);
            List<String> back = new ArrayList<>();
            List<String> own = new ArrayList<>();
            List<String> user = new ArrayList<>();
            for (Tool t : Tool.values()) {
                if (env.containsKey(ShellEnv.projectVariable(t))) {
                    if (env.containsKey(ShellEnv.stateVariable(t))) {
                        own.add(t.toString());
                    } else if (env.get(ShellEnv.userVariable(t)) != null && !env.get(ShellEnv.userVariable(t)).isBlank()) {
                        user.add(t + " back to your own " + t.homeVariable());
                    } else {
                        back.add(t.toString());
                    }
                }
            }
            String where = "left the project" + (left == null ? "" : " in " + Changes.tilde(left));
            if (own.isEmpty() && user.isEmpty()) {
                return where + ": back to the global versions";
            }
            List<String> parts = new ArrayList<>();
            if (!back.isEmpty()) {
                parts.add(String.join(", ", back) + " back to the global " + (back.size() == 1 ? "version" : "versions"));
            }
            if (!user.isEmpty()) {
                parts.add(String.join(", ", user));
            }
            if (!own.isEmpty()) {
                parts.add("this shell's own " + String.join(", ", own) + (own.size() == 1 ? " stays" : " stay"));
            }
            return where + (back.isEmpty() && user.isEmpty() ? "; " : ": ") + String.join("; ", parts);
        }
        String where = Changes.tilde(plan.project().get().dir().toString()) + "/" + plan.project().get().source();
        List<String> tools = new ArrayList<>();
        for (Map.Entry<Tool, Installed> e : plan.applied().entrySet()) {
            tools.add(e.getKey() + " " + e.getValue().label());
        }
        String none = plan.project().get().tools().isEmpty() ? " (it pins nothing)" : " (nothing it pins is installed)";
        return "using " + where + (tools.isEmpty() ? none : ": " + String.join(", ", tools));
    }

    /** The folder of the first file in a {@link ShellEnv#PROJECT} value, or null. */
    static String appliedDir(String applied) {
        if (applied == null || applied.isBlank()) {
            return null;
        }
        // path:mtime;path:mtime... - a path may hold ; or :, so the first file ends at :<digits>; or the end
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?):-?\\d+(;|" + ProjectShell.MISSING
                + "|$)").matcher(applied);
        String first = m.find() ? m.group(1) : applied;
        try {
            Path file = Path.of(first);
            return file.getParent() == null ? null : file.getParent().toString();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }
}
