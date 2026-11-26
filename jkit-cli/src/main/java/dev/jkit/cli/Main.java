package dev.jkit.cli;

import dev.jkit.core.WorkDirs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Visible;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import sun.misc.SignalHandler;

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
            PruneCommand.class,
            ConfigCommand.class,
            SelfUpdateCommand.class,
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
    private static final Set<String> MACHINE_STDOUT = Set.of("env", "hook-env", "hook", "completion");

    public static void main(String[] args) {
        // messages carry text from project files and the network: never raw escape sequences
        System.setErr(SafeOutput.wrap(System.err));
        // --json documents escape their own control characters (JsonOut); \xNN would break the JSON
        List<String> own = Arrays.asList(args);
        if (own.contains("--")) {
            own = own.subList(0, own.indexOf("--"));
        }
        if (args.length == 0 || !MACHINE_STDOUT.contains(args[0]) && !own.contains(JsonOut.FLAG)) {
            System.setOut(SafeOutput.wrap(System.out));
        }
        if (!cwdExists()) {
            // the folder was deleted under the shell: say so plainly instead of a stack trace
            if (args.length >= 1 && args[0].equals("hook-env")) {
                System.exit(0); // nothing to switch; the next cd runs the hook again
            }
            JkitException gone = new JkitException("the current folder no longer exists",
                    "cd to another folder, then run jkit again");
            printError(gone);
            if (own.contains(JsonOut.FLAG)) {
                // written by hand: Jackson's setup reads system properties, which fail without a folder
                System.out.println("{\n  \"schemaVersion\" : " + JsonOut.SCHEMA_VERSION + ",\n  \"error\" : {\n"
                        + "    \"message\" : \"" + gone.getMessage() + "\",\n    \"hints\" : [ \"" + gone.hints().getFirst()
                        + "\" ],\n    \"exitCode\" : " + gone.exitCode() + "\n  }\n}");
            }
            System.exit(gone.exitCode());
        }
        if (args.length >= 1 && args.length <= 2 && args[0].equals("hook-env")) {
            // the cd hook runs this on every directory change: skip building the whole command line
            HookEnvCommand hookEnv = new HookEnvCommand();
            hookEnv.shell = args.length == 2 ? args[1] : null;
            System.exit(hookEnv.call());
        }
        cleanUpOnSignals();
        int code;
        try {
            code = run(args);
        } catch (OutOfMemoryError | StackOverflowError e) {
            // the HTTP and archive limits should prevent this; if not, still one line and one document
            String what = e instanceof OutOfMemoryError ? "out of memory" : "out of stack (a deeply nested input)";
            System.err.println("error: " + what);
            if (own.contains(JsonOut.FLAG)) {
                System.out.println("{\"schemaVersion\":" + JsonOut.SCHEMA_VERSION
                        + ",\"error\":{\"message\":\"" + what + "\",\"hints\":[],\"exitCode\":1}}");
            }
            code = 1;
        }
        Signals.awaitHandler();
        System.exit(code);
    }

    /**
     * The message with the home folder shortened to ~, as the rest of jkit's output shows paths:
     * only where a path starts (after a space, a parenthesis or the start), and only the whole folder.
     */
    static String homeAsTilde(String message, String home) {
        if (message == null || home == null || home.isEmpty() || home.equals(File.separator)) {
            return message;
        }
        Pattern p = Pattern.compile("(^|[\\s(])" + Pattern.quote(home)
                + "(?=[/\\\\\\s:;,)]|$)"); // either slash: Windows paths can come with /
        return p.matcher(message).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "~"));
    }

    /**
     * Ctrl-C (SIGINT), SIGTERM and SIGHUP stop jkit as before, but first delete the folder an
     * install is downloading into. SIGTERM and SIGHUP then exit 143 and 129. After SIGINT jkit
     * ends from the signal itself, which a shell shows as 130 too: a bash script running jkit
     * stops on Ctrl-C only when its command died from SIGINT (as PID 1, and on Windows, it exits
     * 130). jkit exec puts its own handlers in place while its command runs. A signal the shell
     * set to be ignored (a background job's SIGINT) stays ignored.
     */
    private static void cleanUpOnSignals() {
        for (String name : List.of("INT", "TERM", "HUP")) {
            List<Signals.Saved> saved = Signals.handle(() -> {
                // first: the main thread, which may fail now that its folder goes, then leaves
                // the message and the exit code to this handler (Signals.awaitHandler)
                WorkDirs.interrupt();
                List<Path> deleted = WorkDirs.deleteAll();
                if (!deleted.isEmpty()) {
                    System.err.println();
                    System.err.println("interrupted: nothing was installed; deleted the partial download in "
                            + Changes.tilde(deleted.getFirst().toString()));
                }
                if (name.equals("INT")) {
                    Signals.raiseInt(System.getenv()); // where it can't: exit 130 below
                }
                System.exit(128 + signalNumber(name));
            }, name);
            if (!saved.isEmpty() && saved.getFirst().previous() == SignalHandler.SIG_IGN) {
                Signals.restore(saved);
            }
        }
    }

    private static int signalNumber(String name) {
        return switch (name) {
            case "HUP" -> 1;
            case "INT" -> 2;
            default -> 15; // TERM
        };
    }

    private static boolean cwdExists() {
        try {
            String dir = System.getProperty("user.dir");
            return dir != null && !dir.isEmpty() && Files.isDirectory(Path.of(dir));
        } catch (RuntimeException | Error e) { // a native image reads user.dir lazily and throws an Error
            return false;
        }
    }

    static int run(String... args) {
        // only jkit's own arguments: everything after -- belongs to the command jkit exec runs
        List<String> all = Arrays.asList(args);
        List<String> list = all.contains("--") ? all.subList(0, all.indexOf("--")) : all;
        boolean json = list.contains(JsonOut.FLAG);
        CommandLine cli = new CommandLine(new Main());
        // no colors: SafeOutput would print picocli's escape codes as text, and plain help reads fine
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        cli.setExecutionExceptionHandler((ex, cmd, parsed) -> {
            Signals.awaitHandler(); // an error that Ctrl-C caused (its folder deleted) isn't one to show
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
            JkitException usage = usageError(ex, all);
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
            StringWriter text = new StringWriter();
            cli.setOut(new PrintWriter(text, true));
            int code = cli.execute(all.stream().filter(a -> !a.equals(JsonOut.FLAG)).toArray(String[]::new));
            if (code == 0) {
                Map<String, Object> doc = JsonOut.document();
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
    static JkitException usageError(CommandLine.ParameterException ex, List<String> args) {
        CommandLine cmd = ex.getCommandLine();
        List<String> hints = new ArrayList<>();
        boolean exec = cmd.getCommandName().equals("exec");
        if (!exec && ex instanceof CommandLine.UnmatchedArgumentException unmatched
                && !unmatched.getSuggestions().isEmpty()) {
            hints.add("did you mean: " + unmatched.getSuggestions().getFirst() + "?");
        }
        if (exec && !args.contains("--")) {
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
        System.err.println("error: " + homeAsTilde(Visible.of(Errors.capped(e.getMessage())),
                System.getProperty("user.home")));
        // hints keep full paths: they can be commands, where a quoted ~ isn't expanded
        for (String hint : e.hints()) {
            System.err.println("  " + Visible.of(Errors.capped(hint)));
        }
    }
}
