package dev.jkit.cli;

import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.Envs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "reset",
        description = {"Clears a choice made in this shell with jkit use.",
                "The project's version (from jkit sync) or the global one applies again.",
                "With --global, clears the global default instead: new shells get no version",
                "of that tool from jkit.",
                "",
                "  jkit reset jdk",
                "  jkit reset maven --global"})
final class ResetCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>", completionCandidates = Candidates.Tools.class,
            converter = ToolConverter.class)
    Tool tool;

    @CommandLine.Option(names = "--global", description = "Clear the global default; installs stay.")
    boolean global;

    @CommandLine.Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /** Parsing here, not in call(), so an unknown tool is a usage error (exit 2). */
    static final class ToolConverter implements CommandLine.ITypeConverter<Tool> {
        @Override
        public Tool convert(String value) {
            return Tool.parse(value.trim());
        }
    }

    @Override
    public Integer call() throws Exception {
        return run(Context.local());
    }

    int run(Context ctx) throws Exception {
        if (global) {
            return resetGlobal(ctx);
        }
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active", Changes.hookHint(ctx));
        }
        PrintStream say = json.json ? ctx.err : ctx.out;
        if (!ShellEnv.hasChoice(ctx.env, tool)) {
            // nothing of jkit's to take away: a *_HOME the user set stays as it is
            say.println("nothing to reset: this shell has no " + tool + " from jkit use or a project");
            Changes report = new Changes(ctx);
            String own = ctx.env.get(tool.homeVariable());
            if (own != null && !own.isBlank() && !ShellEnv.isGlobal(own, ctx.home, tool)) {
                report.note(tool.homeVariable() + " stays as you set it: " + Changes.tilde(own));
            }
            report.print(say);
            printJson(ctx, "shell", false, report);
            return 0;
        }
        // the project's install applies again only while this folder's project still pins it
        String projectVar = ShellEnv.projectVariable(tool);
        Map<String, String> env = Envs.copy(ctx.env);
        String applied = env.get(projectVar);
        Path project = null;
        if (applied != null && !applied.isBlank()) {
            ToolState state = ToolStates.read(ctx.env, Path.of("").toAbsolutePath(),
                    ctx.home, ctx.store, ctx.config, ctx.os::readLink).get(tool);
            project = state.pinInstall() == null ? null : state.pinInstall().home();
        }
        Map<String, String> changes;
        if (project == null) {
            env.remove(projectVar);
            changes = ShellEnv.reset(env, ctx.home, tool, false);
        } else {
            env.put(projectVar, project.toString());
            changes = ShellEnv.reset(env, ctx.home, tool, tool == Tool.JDK && EnvCommand.isGraalVm(ctx, project));
        }
        if (applied != null && !Objects.equals(env.get(projectVar), applied)) {
            changes.put(projectVar, env.get(projectVar));
        }
        ProjectShell.writeEnvFile(ctx, envFile, changes);
        say.println("reset " + tool + " in this shell");
        Changes report = new Changes(ctx).env(changes);
        if (report.isEmpty()) {
            // only jkit's own record of the choice went
            report.note("its " + tool.homeVariable() + " and PATH stay as they were: the " + tool
                    + " it goes back to is the one it had");
        }
        report.print(say);
        printJson(ctx, "shell", true, report);
        return 0;
    }

    /** {@code {schemaVersion, scope: shell|global, tool, changed, changes: […], notes: […]}}. */
    private void printJson(Context ctx, String scope, boolean changed, Changes report) {
        if (!json.json) {
            return;
        }
        Map<String, Object> doc = JsonOut.document();
        doc.put("scope", scope);
        doc.put("tool", tool.id());
        doc.put("changed", changed);
        doc.put("changes", report.json());
        doc.put("notes", report.notes());
        ctx.out.println(JsonOut.write(doc));
    }

    /** Removes the global link: the install stays, new shells just don't get it. */
    private int resetGlobal(Context ctx) throws Exception {
        Path link = ctx.home.global(tool);
        Changes report = new Changes(ctx);
        PrintStream say = json.json ? ctx.err : ctx.out;
        if (ctx.os.readLink(link) == null) {
            say.println("no global " + tool + " to clear");
            printJson(ctx, "global", false, report);
            return 0;
        }
        try (var _ = ctx.store.linkLock()) {
            ctx.os.removeLink(link);
        }
        report.link(link, null);
        say.println("cleared the global " + tool);
        report.note("installs stay; new shells get no " + tool + " from jkit until: jkit use " + tool + "@<version> --global");
        if (link.toString().equals(ctx.env.get(tool.homeVariable()))) {
            report.note("this shell's " + tool.homeVariable() + " pointed there: open a new shell, or: jkit use "
                    + tool + "@<version>");
        }
        report.print(say);
        printJson(ctx, "global", true, report);
        return 0;
    }
}
