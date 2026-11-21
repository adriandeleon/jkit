package dev.jkit.cli;

import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "reset", description = "Clears a shell-level choice; the global version applies again.")
final class ResetCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>", completionCandidates = Candidates.Tools.class)
    String toolText;

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        Tool tool = Tool.parse(toolText);
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active", "add to ~/.bashrc:  eval \"$(jkit hook bash)\"");
        }
        java.util.Map<String, String> changes = ShellEnv.reset(ctx.env, ctx.home, tool);
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
