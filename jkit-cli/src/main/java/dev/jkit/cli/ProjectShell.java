package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.JkitException;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Project-file steps shared by pin, unpin and sync: which file to edit, and applying a project to this shell. */
final class ProjectShell {
    private ProjectShell() { }

    /**
     * The {@code jkit.toml} that pin and unpin edit: {@code JKIT_PROJECT_FILE} if set, else the
     * project file found from {@code cwd}, else {@code jkit.toml} in {@code cwd} (which may not exist
     * yet). With {@code here}, always {@code cwd}'s. Refuses other formats, which jkit only reads.
     */
    static Path target(Context ctx, Path cwd, boolean here, String retry) {
        if (here) {
            Path hidden = cwd.resolve(ProjectFile.HIDDEN_TOML);
            return !Files.isRegularFile(cwd.resolve(ProjectFile.JKIT_TOML)) && Files.isRegularFile(hidden)
                    ? hidden : cwd.resolve(ProjectFile.JKIT_TOML);
        }
        Optional<ProjectSpec> found = ProjectFile.find(cwd, ctx.env);
        if (found.isEmpty()) {
            String override = ctx.env.get(ProjectFile.OVERRIDE);
            if (override != null && override.isBlank()) {
                throw new JkitException(ProjectFile.OVERRIDE + " is set but empty, which turns project files off",
                        "unset it, or write to this folder's jkit.toml: " + retry + " --here");
            }
            return cwd.resolve(ProjectFile.JKIT_TOML);
        }
        Path file = found.get().files().getFirst();
        String name = file.getFileName().toString();
        if (found.get().files().size() > 1 || name.equals(ProjectFile.SDKMANRC) || name.equals(ProjectFile.TOOL_VERSIONS)
                || name.equals(ProjectFile.JAVA_VERSION)) {
            String files = found.get().source();
            throw new JkitException("the project file here is " + Changes.tilde(found.get().dir().toString())
                    + "/" + files + ", which jkit reads but doesn't write",
                    "create a jkit.toml in this folder instead: " + retry + " --here"
                            + (found.get().dir().equals(cwd) ? " (it then wins over " + files + ")" : ""));
        }
        return file;
    }

    /**
     * Applies the project found from {@code dir} to this shell through the hook, recording the
     * variables in {@code report}; pins that aren't installed, and tools the shell chose with
     * {@code jkit use}, get a note. Without the hook it changes nothing and says so. With no
     * project, it takes away what an earlier sync applied.
     */
    static void apply(Context ctx, Path dir, Changes report) throws Exception {
        ToolStates.Result states = ToolStates.read(ctx.env, dir, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
        if (states.projectError() != null) {
            throw new JkitException(states.projectError());
        }
        String file = states.project().map(ProjectSpec::source).orElse("the project file");
        Map<Tool, Path> homes = new EnumMap<>(Tool.class);
        for (ToolState s : states.tools()) {
            if (s.pin() == null) {
                continue;
            }
            if (s.pinInstall() == null) {
                report.note(file + " wants " + StatusCommand.pinText(s.pin()) + ", not installed: jkit sync");
                continue;
            }
            homes.put(s.tool(), s.pinInstall().home());
            if (s.shellChoice() != null && !same(s.shellChoice(), s.pinInstall().home())) {
                report.note("this shell chose its own " + s.tool() + " with jkit use, which wins over " + file
                        + ": jkit reset " + s.tool() + " to follow " + file);
            }
        }
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            if (!homes.isEmpty()) {
                report.note("the jkit shell hook isn't active, so this shell is unchanged; "
                        + "run one command with these versions: jkit exec -- <command>, or eval \"$(jkit env)\"");
            }
            return;
        }
        Map<String, String> changes = ShellEnv.project(ctx.env, ctx.home, homes,
                EnvCommand.isGraalVm(ctx, homes.get(Tool.JDK)));
        Files.writeString(Path.of(envFile), ShellEnv.format(changes, ctx.env.getOrDefault("JKIT_SHELL", "bash")));
        report.env(changes);
    }

    /** The install whose home is {@code home} (following links), or null. */
    static Installed installAt(Context ctx, Tool tool, Path home) throws IOException {
        if (home == null) {
            return null;
        }
        for (Installed i : ctx.store.list(tool.storeTool())) {
            if (same(i.home(), home)) {
                return i;
            }
        }
        return null;
    }

    private static boolean same(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }
}
