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
            Context ctx = Context.lenient(dev.jkit.model.Envs.system(), System.out);
            String name = shell != null ? shell : ctx.env.getOrDefault("JKIT_SHELL", "bash");
            ctx.out.print(run(ctx, Path.of("").toAbsolutePath(), name, ctx.err));
        } catch (Exception | Error e) {
            System.err.println("jkit: couldn't check for a project file here: " + dev.jkit.model.Visible.of(e.getMessage()));
        }
        return 0;
    }

    /** The shell code to evaluate ("" when nothing changes), telling {@code err} what it changes. */
    static String run(Context ctx, Path cwd, String shell, PrintStream err) throws Exception {
        List<Path> files = ProjectFile.locate(cwd, ctx.env);
        String key = files.isEmpty() ? null : ProjectFile.key(files);
        String applied = ctx.env.get(ShellEnv.PROJECT);
        if (key == null && applied == null && !hasProjectState(ctx.env)) {
            return ""; // the common case outside projects: no files, nothing applied
        }
        if (key != null && key.equals(applied)) {
            return ""; // still the same project, unchanged
        }
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
            return ShellEnv.format(remember, shell);
        }
        Map<String, String> changes = plan.changes();
        if (recheck && !changesMoreThanTheKey(ctx.env, changes)) {
            return ShellEnv.format(changes, shell);
        }
        if (plan.project().isEmpty() && !hasProjectState(ctx.env)) {
            // leaving a project that never applied anything (nothing installed, or a broken file):
            // only the bookkeeping changes, so there is nothing to announce
            return ShellEnv.format(changes, shell);
        }
        err.println("jkit: " + headline(plan, applied));
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
        if (plan.project().isEmpty()) {
            String left = appliedDir(applied);
            return "left the project" + (left == null ? "" : " in " + Changes.tilde(left))
                    + ": back to the global versions";
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
