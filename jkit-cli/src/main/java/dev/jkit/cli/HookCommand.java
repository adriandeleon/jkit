package dev.jkit.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "hook", description = "Prints the shell hook, e.g. eval \"$(jkit hook bash)\" in ~/.bashrc.")
final class HookCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<shell>", description = "bash, zsh, fish or pwsh.")
    String shell;

    @Override
    public Integer call() {
        Context ctx = Context.create(false, true);
        String name = shell != null ? shell : ctx.platform.isWindows() ? "pwsh" : "bash";
        ctx.out.print(ctx.os.hook(name, ctx.home.root()));
        return 0;
    }
}
