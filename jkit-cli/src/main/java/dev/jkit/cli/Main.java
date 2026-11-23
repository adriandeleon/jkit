package dev.jkit.cli;

import dev.jkit.model.JkitException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "jkit",
        mixinStandardHelpOptions = true,
        scope = CommandLine.ScopeType.INHERIT, // --help and --version on every subcommand too
        versionProvider = VersionProvider.class,
        description = "Installs and switches JDKs, Maven, Gradle and sbt.",
        subcommands = {
            SearchCommand.class,
            InstallCommand.class,
            UpgradeCommand.class,
            UseCommand.class,
            ResetCommand.class,
            ListCommand.class,
            StatusCommand.class,
            EnvCommand.class,
            ExecCommand.class,
            PinCommand.class,
            UnpinCommand.class,
            SyncCommand.class,
            HookEnvCommand.class,
            DoctorCommand.class,
            RemoveCommand.class,
            HookCommand.class,
            CompletionCommand.class,
            CopyrightCommand.class,
            CommandLine.HelpCommand.class,
        })
public final class Main implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    /** Commands whose stdout is shell code to eval or a script, not text for a person. */
    private static final java.util.Set<String> MACHINE_STDOUT = java.util.Set.of("env", "hook-env", "hook", "completion");

    public static void main(String[] args) {
        // messages carry text from project files and the network: never raw escape sequences
        System.setErr(SafeOutput.wrap(System.err));
        if (args.length == 0 || !MACHINE_STDOUT.contains(args[0])) {
            System.setOut(SafeOutput.wrap(System.out));
        }
        if (args.length >= 1 && args.length <= 2 && args[0].equals("hook-env")) {
            // the cd hook runs this on every directory change: skip building the whole command line
            HookEnvCommand hookEnv = new HookEnvCommand();
            hookEnv.shell = args.length == 2 ? args[1] : null;
            System.exit(hookEnv.call());
        }
        System.exit(run(args));
    }

    static int run(String... args) {
        // only jkit's own arguments: everything after -- belongs to the command jkit exec runs
        java.util.List<String> all = java.util.Arrays.asList(args);
        java.util.List<String> list = all.contains("--") ? all.subList(0, all.indexOf("--")) : all;
        boolean json = list.contains(JsonOut.FLAG);
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((ex, cmd, parsed) -> {
            if (System.getenv("JKIT_DEBUG") != null) {
                ex.printStackTrace();
            }
            JkitException je = Errors.describe(ex);
            printError(je);
            if (json) {
                JsonOut.print(System.out, JsonOut.error(je, je.exitCode()));
            }
            return je.exitCode();
        });
        cli.setParameterExceptionHandler((ex, a) -> {
            Throwable cause = ex.getCause();
            if (cause instanceof JkitException je) {
                printError(je);
                if (json) {
                    JsonOut.print(System.out, JsonOut.error(je, CommandLine.ExitCode.USAGE));
                }
                return CommandLine.ExitCode.USAGE;
            }
            JkitException usage = usageError(ex);
            printError(usage);
            if (json) {
                JsonOut.print(System.out, JsonOut.error(usage, CommandLine.ExitCode.USAGE));
            }
            return CommandLine.ExitCode.USAGE;
        });
        if (json && (list.contains("-h") || list.contains("--help") || list.contains("help")
                || list.contains("-V") || list.contains("--version"))) {
            // help and version under --json: still one JSON document on stdout. Parse errors print
            // their own error document through the handler above.
            java.io.StringWriter text = new java.io.StringWriter();
            cli.setOut(new java.io.PrintWriter(text, true));
            int code = cli.execute(all.stream().filter(a -> !a.equals(JsonOut.FLAG)).toArray(String[]::new));
            if (code == 0) {
                java.util.Map<String, Object> doc = JsonOut.document();
                doc.put(list.contains("-V") || list.contains("--version") ? "version" : "help", text.toString().strip());
                System.out.println(JsonOut.write(doc));
            }
            return code;
        }
        return cli.execute(args);
    }

    /**
     * A parse error as one line plus hints, not the whole usage screen: "did you mean" for a
     * mistyped command, the missing -- for exec, and the command's synopsis.
     */
    static JkitException usageError(CommandLine.ParameterException ex) {
        CommandLine cmd = ex.getCommandLine();
        java.util.List<String> hints = new java.util.ArrayList<>();
        boolean exec = cmd.getCommandName().equals("exec");
        if (!exec && ex instanceof CommandLine.UnmatchedArgumentException unmatched
                && !unmatched.getSuggestions().isEmpty()) {
            hints.add("did you mean: " + unmatched.getSuggestions().getFirst() + "?");
        }
        if (exec) {
            hints.add("put -- before the command to run: jkit exec [<tool>@<version> [vendor]]... -- <command> [args]");
        }
        String name = cmd.getCommandSpec().qualifiedName();
        String synopsis = cmd.getHelp().synopsis(0).strip().replaceFirst("^Usage:\\s*", "");
        if (cmd.getParent() != null) {
            hints.add("usage: " + synopsis.replaceAll("\\s+", " "));
        }
        hints.add("more: " + name + " --help");
        return new JkitException(CommandLine.ExitCode.USAGE, ex.getMessage(), hints.toArray(String[]::new));
    }

    private static void printError(JkitException e) {
        System.err.println("error: " + e.getMessage());
        for (String hint : e.hints()) {
            System.err.println("  " + hint);
        }
    }
}
