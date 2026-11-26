package dev.jkit.cli;

import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.Envs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
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

    /** Parsing here, not in call(), so an unknown tool is a usage error (exit 2). */
    static final class ToolConverter implements CommandLine.ITypeConverter<Tool> {
        @Override
        public Tool convert(String value) {
            return Tool.parse(value.trim());
        }
    }

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        if (global) {
            return resetGlobal(ctx);
        }
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active", "load it in new shells: " + Changes.HOOK_SETUP);
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
        ctx.out.println("reset " + tool + " in this shell");
        Changes report = new Changes(ctx).env(changes);
        if (report.isEmpty()) {
            report.note("nothing changed: this shell had no " + tool + " of its own");
        }
        report.print(ctx.out);
        return 0;
    }

    /** Removes the global link: the install stays, new shells just don't get it. */
    private int resetGlobal(Context ctx) throws Exception {
        Path link = ctx.home.global(tool);
        Changes report = new Changes(ctx);
        if (ctx.os.readLink(link) == null) {
            ctx.out.println("no global " + tool + " to clear");
            return 0;
        }
        try (AutoCloseable lock = ctx.store.linkLock()) {
            ctx.os.removeLink(link);
        }
        report.link(link, null);
        ctx.out.println("cleared the global " + tool);
        report.note("installs stay; new shells get no " + tool + " from jkit until: jkit use " + tool + "@<version> --global");
        if (link.toString().equals(ctx.env.get(tool.homeVariable()))) {
            report.note("this shell's " + tool.homeVariable() + " pointed there: open a new shell, or: jkit use "
                    + tool + "@<version>");
        }
        report.print(ctx.out);
        return 0;
    }
}
