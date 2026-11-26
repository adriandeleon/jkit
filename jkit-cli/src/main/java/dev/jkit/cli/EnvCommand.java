package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.Envs;
import dev.jkit.model.JkitException;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "env",
        description = {"Prints the environment for a new process here.",
                "Global versions, overridden by the project file. For CI, IDEs and scripts",
                "without the hook: eval \"$(jkit env)\". Never uses the network.",
                "To switch the current shell to the project's versions, use jkit sync.",
                "",
                "Windows: --install sets JAVA_HOME and the other *_HOME variables, and PATH, in",
                "your user environment to jkit's global links, so cmd.exe, IDEs and programs",
                "started from Explorer get the global versions; jkit use --global then reaches",
                "them with no further change. --uninstall removes what --install set."})
final class EnvCommand implements Callable<Integer> {
    static final List<String> SHELLS = List.of("bash", "zsh", "fish", "pwsh", "powershell"); // powershell: Windows PowerShell 5.1

    @Option(names = "--shell", paramLabel = "<shell>", completionCandidates = Candidates.Shells.class,
            description = "Syntax: bash (default), zsh, fish or pwsh.")
    String shell;

    @Option(names = "--no-project", description = "Ignore project files; global versions only.")
    boolean noProject;

    /** The old name of --no-project, kept so scripts keep working. */
    @Option(names = "--global", hidden = true)
    boolean global;

    @Option(names = "--dir", paramLabel = "<path>", description = "The folder to look for a project file from (default: here).")
    Path dir;

    @Option(names = "--install", description = "Windows: put the global versions in your user environment.")
    boolean install;

    @Option(names = "--uninstall", description = "Windows: take out what --install put there.")
    boolean uninstall;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /** What a new process gets: the chosen install folder per tool, and the variables to set. */
    record Result(Path dir, Optional<ProjectSpec> project, Map<Tool, Path> homes, Map<String, String> variables) { }

    /**
     * The shell that will read the output: the one that started jkit when it is a known shell
     * ({@code eval "$(jkit env)"} in a bash script run from fish is bash), else the hook's
     * JKIT_SHELL, else bash.
     */
    static String defaultShell(Map<String, String> env) {
        String parent = ProcessHandle.current().parent()
                .flatMap(p -> p.info().command())
                .map(c -> Path.of(c).getFileName().toString().toLowerCase(Locale.ROOT))
                .map(c -> c.endsWith(".exe") ? c.substring(0, c.length() - 4) : c)
                .orElse("");
        if (parent.startsWith("-")) {
            parent = parent.substring(1); // login shells
        }
        if (SHELLS.contains(parent)) {
            return parent;
        }
        if (parent.equals("sh") || parent.equals("dash") || parent.equals("ksh")) {
            return "bash"; // same syntax for export/unset
        }
        return env.getOrDefault("JKIT_SHELL", "bash");
    }

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.readOnly();
        if (install || uninstall) {
            if (install && uninstall) {
                throw new JkitException(CommandLine.ExitCode.USAGE, "--install or --uninstall, not both");
            }
            if (shell != null || dir != null || noProject || global) {
                throw new JkitException(CommandLine.ExitCode.USAGE,
                        "--" + (install ? "install" : "uninstall") + " goes alone: it sets the global versions, "
                                + "for every program, not one shell's or one project's");
            }
            return UserEnvSetup.run(ctx, install, json.json);
        }
        String syntax = shell != null ? shell : defaultShell(ctx.env);
        if (!SHELLS.contains(syntax)) {
            if (!json.json) {
                ctx.out.println(failLine("bash"));
            }
            throw new JkitException(CommandLine.ExitCode.USAGE, "unknown shell '" + syntax + "'", "shells: " + String.join(", ", SHELLS));
        }
        Path cwd = Path.of("").toAbsolutePath();
        Result result;
        try {
            result = compute(ctx, dir == null ? cwd : cwd.resolve(dir).normalize(), noProject || global);
        } catch (Exception e) {
            if (!json.json) {
                // The error goes to stderr; this line makes eval "$(jkit env)" fail too, so a set -e
                // script or a CI step stops instead of running with half an environment.
                ctx.out.println(failLine(syntax));
            }
            throw e;
        }
        if (json.json) {
            ctx.out.println(JsonOut.write(json(result)));
        } else if (result.homes().isEmpty()) {
            ctx.err.println("nothing to set: no project file here and no global versions · see: jkit status");
        } else {
            ctx.out.print(ShellEnv.format(result.variables(), syntax));
        }
        return 0;
    }

    /** Shell code that fails when evaluated, printed on stdout when jkit env fails. */
    static String failLine(String syntax) {
        return switch (syntax) {
            case "pwsh", "powershell" -> "throw 'jkit env failed: see the error above'";
            default -> "false";
        };
    }

    /**
     * Global links, overlaid by the project's pins; shell choices don't count. A pin with no matching
     * install is an error (exit 3) so a CI step never runs with half an environment.
     */
    static Result compute(Context ctx, Path dir, boolean ignoreProject) throws Exception {
        return compute(ctx, dir, ignoreProject, Set.of(), "jkit env --no-project");
    }

    /**
     * As above, but a missing pin for a tool in {@code chosen} is no error: the caller sets that tool
     * itself. {@code ignoreCommand} is the hint for skipping the project file.
     */
    static Result compute(Context ctx, Path dir, boolean ignoreProject, Set<Tool> chosen,
            String ignoreCommand) throws Exception {
        if (!Files.isDirectory(dir)) {
            throw new JkitException(dir + " is not a folder");
        }
        Map<String, String> env = Envs.copy(ctx.env);
        if (ignoreProject) {
            env.put(ProjectFile.OVERRIDE, "");
        }
        ToolStates.Result states = ToolStates.read(env, dir, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
        if (states.projectError() != null) {
            throw new JkitException(states.projectError());
        }
        states.project().ifPresent(p -> p.warnings().forEach(w -> ctx.err.println("warning: " + Changes.tilde(w))));

        Map<Tool, Path> homes = new EnumMap<>(Tool.class);
        List<ToolState> missing = new ArrayList<>();
        for (ToolState s : states.tools()) {
            if (s.pin() != null) {
                if (s.pinInstall() == null && chosen.contains(s.tool())) {
                    continue; // the caller names its own version
                } else if (s.pinInstall() == null) {
                    missing.add(s);
                } else {
                    homes.put(s.tool(), s.pinInstall().home());
                }
            } else if (s.global() != null) {
                if (s.globalMissing()) {
                    ctx.err.println("warning: global " + s.tool() + " → " + Changes.tilde(s.global().toString())
                            + ", which no longer exists; skipped");
                } else {
                    homes.put(s.tool(), ctx.home.global(s.tool()));
                }
            }
        }
        if (!missing.isEmpty()) {
            // each pin under the file that set it: .sdkmanrc wants jdk 21; .tool-versions wants maven 3.9
            Map<String, List<String>> wants = new LinkedHashMap<>();
            for (ToolState s : missing) {
                wants.computeIfAbsent(ProjectShell.pinFile(states.project(), s.pin().tool()), f -> new ArrayList<>())
                        .add(StatusCommand.pinText(s.pin()));
            }
            List<String> parts = new ArrayList<>();
            wants.forEach((file, pins) -> parts.add(file + " wants " + String.join(", ", pins)));
            List<String> hints = new ArrayList<>();
            for (ToolState s : missing) {
                hints.add("install it: " + StatusCommand.installCommand(s.pin()));
            }
            hints.add("or ignore the project file: " + ignoreCommand);
            throw new JkitException(JkitException.NOT_INSTALLED,
                    String.join("; ", parts) + ", not installed", hints.toArray(String[]::new));
        }
        Map<String, String> variables = ShellEnv.fresh(ctx.env, ctx.home, homes, isGraalVm(ctx, homes.get(Tool.JDK)));
        return new Result(dir, states.project(), homes, variables);
    }

    static boolean isGraalVm(Context ctx, Path jdkHome) throws Exception {
        if (jdkHome == null) {
            return false;
        }
        Path real = jdkHome.toRealPath();
        for (Installed i : ctx.store.list(Tool.JDK)) {
            if (i.home().toRealPath().equals(real)) {
                return i.vendor() != null && i.vendor().isGraalVmBased();
            }
        }
        return false;
    }

    /** {@code {schemaVersion, dir, project, variables: {JAVA_HOME: …}, pathPrepend: [...], path}}. */
    static Map<String, Object> json(Result result) {
        Map<String, Object> doc = JsonOut.document();
        doc.put("dir", result.dir());
        doc.put("project", result.project().map(p -> (Object) JsonOut.fields(
                "dir", p.dir(), "files", p.files(), "warnings", p.warnings())).orElse(null));
        Map<String, Object> variables = JsonOut.fields();
        result.variables().forEach((k, v) -> {
            if (!k.equals("PATH")) {
                variables.put(k, v);
            }
        });
        doc.put("variables", variables);
        List<String> prepend = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (t.onPath() && result.homes().containsKey(t)) {
                prepend.add(result.homes().get(t).resolve("bin").toString());
            }
        }
        doc.put("pathPrepend", prepend);
        doc.put("path", result.variables().get("PATH"));
        doc.put("pathSeparator", File.pathSeparator);
        return doc;
    }
}
