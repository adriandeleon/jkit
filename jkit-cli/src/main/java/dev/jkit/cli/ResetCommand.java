package dev.jkit.cli;

import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "reset", description = "Clears a shell-level choice; the project's version (from jkit sync) or the global one applies again.")
final class ResetCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>", completionCandidates = Candidates.Tools.class,
            converter = ToolConverter.class)
    Tool tool;

    /** Parsing here, not in call(), so an unknown tool is a usage error (exit 2). */
    static final class ToolConverter implements picocli.CommandLine.ITypeConverter<Tool> {
        @Override
        public Tool convert(String value) {
            return Tool.parse(value.trim());
        }
    }

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active", "load it in new shells: jkit hook bash --install (or zsh, fish)");
        }
        // the project's install applies again only while this folder's project still pins it
        String projectVar = ShellEnv.projectVariable(tool);
        java.util.Map<String, String> env = new java.util.HashMap<>(ctx.env);
        String applied = env.get(projectVar);
        Path project = null;
        if (applied != null && !applied.isBlank()) {
            dev.jkit.core.ToolState state = dev.jkit.core.ToolStates.read(ctx.env, Path.of("").toAbsolutePath(),
                    ctx.home, ctx.store, ctx.config, ctx.os::readLink).get(tool);
            project = state.pinInstall() == null ? null : state.pinInstall().home();
        }
        java.util.Map<String, String> changes;
        if (project == null) {
            env.remove(projectVar);
            changes = ShellEnv.reset(env, ctx.home, tool, false);
        } else {
            env.put(projectVar, project.toString());
            changes = ShellEnv.reset(env, ctx.home, tool, tool == Tool.JDK && EnvCommand.isGraalVm(ctx, project));
        }
        if (applied != null && !java.util.Objects.equals(env.get(projectVar), applied)) {
            changes.put(projectVar, env.get(projectVar));
        }
        Files.writeString(Path.of(envFile), ShellEnv.format(changes, ctx.env.getOrDefault("JKIT_SHELL", "bash")));
        ctx.out.println("reset " + tool + " in this shell");
        Changes report = new Changes(ctx).env(changes);
        if (report.isEmpty()) {
            report.note("nothing changed: this shell had no " + tool + " of its own");
        }
        report.print(ctx.out);
        return 0;
    }
}
