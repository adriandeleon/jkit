package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "exec",
        description = {"Runs one command with the versions jkit picks; no shell is changed.",
                "For scripts, CI and AI agents, where each command starts in a new shell.",
                "Starts from what jkit env gives (global versions, overridden by the project",
                "file); tools named before -- override that.",
                "",
                "  jkit exec -- mvn test",
                "  jkit exec jdk@17 zulu maven@3.9 -- mvn -v",
                "",
                "Exits with the command's exit code (126: can't start it, 127: not found).",
                "Never uses the network and installs nothing: a missing version exits 3."})
final class ExecCommand implements Callable<Integer> {

    @Parameters(paramLabel = "[<tool>[@version] [vendor]]... -- <command> [args]",
            completionCandidates = Candidates.Tools.class)
    List<String> args = new ArrayList<>();

    @Option(names = "--global", description = "Ignore project files; start from the global versions only.")
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
        Plan plan = plan(ctx, dir == null ? cwd : cwd.resolve(dir).normalize(), global, choices(before), command);
        return run(plan, ctx.env);
    }

    /** Tool specs, each optionally followed by a vendor: {@code jdk@17 zulu maven@3.9}. */
    static List<Choice> choices(List<String> words) {
        List<Choice> out = new ArrayList<>();
        for (String word : words) {
            ToolSpec parsed = asSpec(word);
            if (parsed != null) {
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
        try {
            Tool.parse(tool);
        } catch (JkitException e) {
            return null;
        }
        return ToolSpec.parse(word); // a bad version is an error, not a vendor
    }

    static Plan plan(Context ctx, Path dir, boolean ignoreProject, List<Choice> choices, List<String> command)
            throws Exception {
        EnvCommand.Result base = EnvCommand.compute(ctx, dir, ignoreProject);
        Map<Tool, Path> homes = new EnumMap<>(Tool.class);
        homes.putAll(base.homes());
        boolean jdkIsGraalVm = EnvCommand.isGraalVm(ctx, homes.get(Tool.JDK));
        for (Choice c : choices) {
            Vendor vendor = Resolve.vendor(ctx, c.spec(), c.vendor());
            Installed chosen = Resolve.installed(ctx, c.spec(), vendor, c.vendor(), false).getFirst();
            homes.put(c.spec().tool(), chosen.home());
            if (c.spec().tool() == Tool.JDK) {
                jdkIsGraalVm = chosen.vendor() != null && chosen.vendor().isGraalVmBased();
            }
        }
        Map<String, String> variables = new HashMap<>(ShellEnv.fresh(ctx.env, ctx.home, homes, jdkIsGraalVm));
        Path tools = ctx.home.root().resolve("tools").toAbsolutePath().normalize();
        for (Tool t : Tool.values()) {
            variables.put(ShellEnv.stateVariable(t), null); // this shell's jkit use doesn't apply in there
            String current = ctx.env.get(t.homeVariable());
            if (!variables.containsKey(t.homeVariable()) && current != null
                    && Path.of(current).toAbsolutePath().normalize().startsWith(tools)) {
                variables.put(t.homeVariable(), null); // a jkit install whose bin is no longer on PATH
            }
        }
        variables.put("JKIT_ENV_FILE", null); // a jkit use in there must not change the calling shell
        return new Plan(dir, List.copyOf(command), variables);
    }

    /** The child environment: the parent's, with the plan's changes applied. */
    static Map<String, String> childEnv(Map<String, String> parent, Plan plan) {
        Map<String, String> env = new HashMap<>(parent);
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
        List<String> suffixes = windows ? List.of("", ".exe", ".cmd", ".bat") : List.of("");
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            for (String suffix : suffixes) {
                Path candidate = dir.resolve(entry).resolve(name + suffix);
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
        Process process;
        try {
            process = pb.start();
        } catch (java.io.IOException e) {
            throw new JkitException(126, "can't start " + plan.command().getFirst() + ": " + e.getMessage());
        }
        Thread stop = new Thread(process::destroy);
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            return process.waitFor();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException shuttingDown) {
                // the hook is running already
            }
        }
    }
}
