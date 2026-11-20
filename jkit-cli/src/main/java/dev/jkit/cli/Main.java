package dev.jkit.cli;

import dev.jkit.model.JkitException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "jkit",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Installs and switches JDKs, Maven and Gradle.",
        subcommands = {
            SearchCommand.class,
            InstallCommand.class,
            UpgradeCommand.class,
            UseCommand.class,
            ResetCommand.class,
            ListCommand.class,
            RemoveCommand.class,
            HookCommand.class,
            CompletionCommand.class,
            CommandLine.HelpCommand.class,
        })
public final class Main implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String... args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((ex, cmd, parsed) -> {
            if (System.getenv("JKIT_DEBUG") != null) {
                ex.printStackTrace();
            }
            JkitException je = Errors.describe(ex);
            printError(je);
            return je.exitCode();
        });
        cli.setParameterExceptionHandler((ex, a) -> {
            Throwable cause = ex.getCause();
            if (cause instanceof JkitException je) {
                printError(je);
                return CommandLine.ExitCode.USAGE;
            }
            System.err.println("error: " + ex.getMessage());
            ex.getCommandLine().usage(System.err);
            return CommandLine.ExitCode.USAGE;
        });
        return cli.execute(args);
    }

    private static void printError(JkitException e) {
        System.err.println("error: " + e.getMessage());
        for (String hint : e.hints()) {
            System.err.println("  " + hint);
        }
    }
}
