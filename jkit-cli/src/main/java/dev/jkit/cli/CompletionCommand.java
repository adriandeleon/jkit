package dev.jkit.cli;

import dev.jkit.model.JkitException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "completion",
        description = {"Prints the bash completion script.",
                "zsh works through bashcompinit.",
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

    /**
     * picocli's lines for zsh that change the user's shell: COMPLETE_ALIASES changes how every
     * alias completes, and the alias makes compopt mean complete. The script needs neither: it
     * calls compopt only after {@code type compopt}, which zsh's bashcompinit doesn't define.
     */
    static final String ZSH_OPTIONS = "  setopt COMPLETE_ALIASES\n  alias compopt=complete\n";

    /**
     * The bash completion script for the whole command tree. Also works in zsh via bashcompinit,
     * without picocli's {@link #ZSH_OPTIONS}: the hook carries this script, and loading it must not
     * change options or aliases.
     */
    static String script(CommandLine root) {
        String script = AutoComplete.bash("jkit", root);
        String comment = "  # Make alias a distinct command for completion purposes when using zsh (see [4])\n";
        return script.replace(comment + ZSH_OPTIONS, "").replace(ZSH_OPTIONS, "");
    }

    static final class CompletionShells implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return List.of("bash", "zsh").iterator();
        }
    }
}
