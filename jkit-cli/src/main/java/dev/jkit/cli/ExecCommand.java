package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.Envs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "exec",
        description = {"Runs one command with the versions jkit picks.",
                "No shell is changed.",
                "For scripts, CI and AI agents, where each command starts in a new shell.",
                "Starts from what jkit env gives (global versions, overridden by the project",
                "file); tools named before -- override that.",
                "",
                "  jkit exec -- mvn test",
                "  jkit exec jdk@17 zulu maven@3.9 -- mvn -v",
                "",
                "Exits with the command's exit code (126: can't start it, 127: not found).",
                "Never uses the network and installs nothing: a missing version exits 3.",
                "Ctrl-C goes to the command itself (jkit ignores SIGINT while it waits).",
                "To stop it from another process, send jkit SIGTERM: it passes it on, waits,",
                "and exits with the command's code (a second SIGTERM kills the command).",
                "A command ended by SIGINT, SIGTERM or SIGHUP ends jkit with the same signal,",
                "so a script calling jkit exec stops on Ctrl-C as it would without jkit."})
final class ExecCommand implements Callable<Integer> {

    @Parameters(paramLabel = "[<tool>[@version] [vendor]]... -- <command> [args]",
            completionCandidates = Candidates.Tools.class)
    List<String> args = new ArrayList<>();

    @Option(names = "--no-project", description = "Ignore project files; start from the global versions only.")
    boolean noProject;

    /** The old name of --no-project, kept so scripts keep working. */
    @Option(names = "--global", hidden = true)
    boolean global;

    @Option(names = "--dir", paramLabel = "<path>", description = "Run in this folder, and look for a project file from it.")
    Path dir;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /** One tool named before {@code --}: the spec and the vendor typed after it, if any. */
    record Choice(ToolSpec spec, String vendor) { }

    /** What to run and with which variables changed (null value: unset). */
    record Plan(Path dir, List<String> command, Map<String, String> variables) { }

    @Override
    public Integer call() throws Exception {
        List<String> original = spec.commandLine().getParseResult().originalArgs();
        int dash = original.indexOf("--");
        if (dash < 0) {
            throw new JkitException(CommandLine.ExitCode.USAGE, "put -- before the command to run",
                    "e.g. jkit exec jdk@21 -- java -version");
        }
        int tail = original.size() - dash - 1;
        if (tail == 0) {
            throw new JkitException(CommandLine.ExitCode.USAGE, "no command after --", "e.g. jkit exec jdk@21 -- java -version");
        }
        List<String> before = args.subList(0, args.size() - tail);
        List<String> command = args.subList(args.size() - tail, args.size());
        Context ctx = Context.readOnly();
        Path cwd = Path.of("").toAbsolutePath();
        Plan plan = plan(ctx, dir == null ? cwd : cwd.resolve(dir).normalize(), noProject || global, choices(before),
                command);
        return run(plan, ctx.env);
    }

    /** Tool specs, each optionally followed by a vendor: {@code jdk@17 zulu maven@3.9}. */
    static List<Choice> choices(List<String> words) {
        List<Choice> out = new ArrayList<>();
        for (String word : words) {
            ToolSpec parsed = asSpec(word);
            // "graalvm" is a tool and Oracle GraalVM's vendor name: right after a JDK without a
            // vendor it is the vendor, as in jkit use jdk@21 graalvm (graalvm@21 names the tool)
            boolean vendorAfterJdk = word.equals("graalvm") && !out.isEmpty() && out.getLast().vendor() == null
                    && out.getLast().spec().tool() == Tool.JDK;
            if (parsed != null && !vendorAfterJdk) {
                out.add(new Choice(parsed, null));
            } else if (!out.isEmpty() && out.getLast().vendor() == null && out.getLast().spec().tool().hasVendors()) {
                out.set(out.size() - 1, new Choice(out.getLast().spec(), word));
            } else {
                throw new JkitException(CommandLine.ExitCode.USAGE, "'" + word + "' is not a tool"
                        + (out.isEmpty() ? "" : " or a vendor for " + out.getLast().spec().tool()),
                        "before --: tools such as jdk@21 zulu, maven@3.9, gradle, sbt, graalvm@25",
                        "the command goes after --: jkit exec jdk@21 -- " + word);
            }
        }
        return out;
    }

    /** A tool spec, or null when the word names no tool (so it may be a vendor). */
    private static ToolSpec asSpec(String word) {
        String tool = word.contains("@") ? word.substring(0, word.indexOf('@')) : word;
        return Tool.find(tool).isEmpty() ? null : ToolSpec.parse(word); // a bad version is an error, not a vendor
    }

    static Plan plan(Context ctx, Path dir, boolean ignoreProject, List<Choice> choices, List<String> command)
            throws Exception {
        Set<Tool> named = EnumSet.noneOf(Tool.class);
        choices.forEach(c -> named.add(c.spec().tool()));
        EnvCommand.Result base = EnvCommand.compute(ctx, dir, ignoreProject, named,
                "jkit exec --no-project " + (choices.isEmpty() ? "" : "<tools> ") + "-- <command>");
        Map<Tool, Path> homes = new EnumMap<>(Tool.class);
        homes.putAll(base.homes());
        boolean jdkIsGraalVm = EnvCommand.isGraalVm(ctx, homes.get(Tool.JDK));
        for (Choice c : choices) {
            ToolSpec cs = Resolve.lts(ctx, c.spec(), c.vendor(), false);
            Vendor vendor = Resolve.vendor(ctx, cs, c.vendor());
            Installed chosen = Resolve.installed(ctx, cs, vendor, c.vendor(), false, "exec").getFirst();
            homes.put(c.spec().tool(), chosen.home());
            if (c.spec().tool() == Tool.JDK) {
                jdkIsGraalVm = chosen.vendor() != null && chosen.vendor().isGraalVmBased();
            }
        }
        Map<String, String> variables = new HashMap<>(ShellEnv.fresh(ctx.env, ctx.home, homes, jdkIsGraalVm));
        Path tools = ctx.home.root().resolve("tools").toAbsolutePath().normalize();
        for (Tool t : Tool.values()) {
            variables.put(ShellEnv.stateVariable(t), null); // this shell's jkit use doesn't apply in there
            variables.put(ShellEnv.projectVariable(t), null); // nor what jkit sync applied to it
            String current = ctx.env.get(t.homeVariable());
            if (!variables.containsKey(t.homeVariable()) && current != null
                    && Fs.path(current) != null
                    && Fs.path(current).toAbsolutePath().normalize().startsWith(tools)) {
                variables.put(t.homeVariable(), null); // a jkit install whose bin is no longer on PATH
            }
        }
        variables.put(ShellEnv.PROJECT, null);
        variables.put("JKIT_ENV_FILE", null); // a jkit use in there must not change the calling shell
        return new Plan(dir, List.copyOf(command), variables);
    }

    /** The child environment: the parent's, with the plan's changes applied. */
    static Map<String, String> childEnv(Map<String, String> parent, Plan plan) {
        Map<String, String> env = Envs.copy(parent);
        plan.variables().forEach((k, v) -> {
            if (v == null) {
                env.remove(k);
            } else {
                env.put(k, v);
            }
        });
        return env;
    }

    /**
     * The program to start, looked up on the child's PATH: ProcessBuilder would search jkit's own
     * PATH, which still has the old versions first.
     */
    static String executable(String name, String path, Path dir) {
        if (name.contains("/") || name.contains(File.separator)) {
            return name;
        }
        boolean windows = File.separatorChar == '\\';
        // Windows: mvn.cmd before mvn, the Unix script next to it that CreateProcess can't start
        List<String> suffixes = windows ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
        for (String raw : path.split(File.pathSeparator)) {
            // some installers add quoted entries ("C:\Program Files\x"), which aren't valid paths
            Path entry = Fs.path(raw);
            if (entry == null) {
                continue;
            }
            for (String suffix : suffixes) {
                Path candidate;
                try {
                    candidate = dir.resolve(entry).resolve(name + suffix);
                } catch (InvalidPathException e) {
                    continue;
                }
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        throw new JkitException(127, "command not found: " + name,
                "it isn't on the PATH jkit gives the command: see jkit env");
    }

    private static int run(Plan plan, Map<String, String> parent) throws Exception {
        Map<String, String> env = childEnv(parent, plan);
        List<String> command = new ArrayList<>(plan.command());
        command.set(0, executable(command.getFirst(), env.getOrDefault("PATH", ""), plan.dir()));
        ProcessBuilder pb = new ProcessBuilder(command).directory(plan.dir().toFile()).inheritIO();
        pb.environment().clear();
        pb.environment().putAll(env);
        // Ctrl-C reaches the whole foreground process group, so the child gets its own SIGINT: a REPL
        // handles it and keeps going, and its exit code is ours. SIGTERM and SIGHUP are passed on as
        // SIGTERM (a second one kills it), and jkit still waits, so its exit code is the child's and
        // a child that ignores TERM is never left running on its own. In place before the child
        // starts: a Ctrl-C as it starts must not end jkit and leave the child running on its own.
        AtomicReference<Process> child = new AtomicReference<>();
        AtomicInteger stops = new AtomicInteger();
        List<Signals.Saved> ignored = Signals.ignore("INT", "QUIT");
        ignored.addAll(Signals.handle(() -> {
            int n = stops.getAndIncrement();
            Process p = child.get();
            if (p != null && n == 0) {
                p.destroy();
            } else if (p != null) {
                p.destroyForcibly();
            }
        }, "TERM", "HUP"));
        Thread stop = null;
        int code;
        try {
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new JkitException(126, "can't start " + plan.command().getFirst() + ": " + e.getMessage());
            }
            child.set(process);
            if (stops.get() > 0) {
                process.destroy(); // a SIGTERM came while it started
            }
            stop = new Thread(process::destroy); // where those signals can't be handled
            Runtime.getRuntime().addShutdownHook(stop);
            code = process.waitFor();
        } finally {
            Signals.restore(ignored);
            if (stop != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(stop);
                } catch (IllegalStateException shuttingDown) {
                    // the hook is running already
                }
            }
        }
        // ended by a signal: so is jkit, or a bash script running jkit exec would carry on after Ctrl-C
        // (bash stops only when the command it waited for died from SIGINT, not when it exited 130)
        Signals.raiseFor(code);
        return code;
    }
}
