package dev.jkit.cli;

import dev.jkit.model.JkitException;
import java.util.concurrent.Callable;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "completion",
        description = {"Prints the bash completion script (zsh works through bashcompinit).",
                "The shell hook and the Linux packages already load it."})
final class CompletionCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<shell>", defaultValue = "bash",
            completionCandidates = CompletionShells.class, description = "bash or zsh (default: bash).")
    String shell;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        if (!shell.equals("bash") && !shell.equals("zsh")) {
            throw new JkitException(CommandLine.ExitCode.USAGE, "no completion script for " + shell + " yet", "supported: bash, zsh");
        }
        System.out.print(script(spec.root().commandLine()));
        return 0;
    }

    /** The bash completion script for the whole command tree. Also works in zsh via bashcompinit. */
    static String script(CommandLine root) {
        return AutoComplete.bash("jkit", root);
    }

    static final class CompletionShells implements Iterable<String> {
        @Override
        public java.util.Iterator<String> iterator() {
            return java.util.List.of("bash", "zsh").iterator();
        }
    }
}
