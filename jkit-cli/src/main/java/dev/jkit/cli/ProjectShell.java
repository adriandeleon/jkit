package dev.jkit.cli;

import dev.jkit.core.AtomicFile;
import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.JkitException;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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
        if (cwd.toString().indexOf('\uFFFD') >= 0) {
            throw new JkitException("this folder's path isn't valid UTF-8, so jkit can't write a project file in it",
                    "rename the folder, or edit jkit.toml by hand");
        }
        Path file = targetName(ctx, cwd, here, retry);
        if (Files.isSymbolicLink(file)) {
            // a cloned repo's jkit.toml could point at ~/.bash_profile or jkit's config.toml
            throw new JkitException(Changes.tilde(file.toString()) + " is a symbolic link, and jkit only writes "
                    + "the file itself", "replace the link with a plain file, or edit the file it points to by hand");
        }
        return file;
    }

    /**
     * Writes the shell code the hook sources next. For PowerShell with a UTF-8 byte-order mark:
     * Windows PowerShell 5.1 reads a script without one in the ANSI code page, which garbles
     * C:\\Users\\José in JAVA_HOME and PATH.
     */
    static void writeEnvFile(Context ctx, String envFile, Map<String, String> changes) throws IOException {
        String shell = ctx.env.getOrDefault("JKIT_SHELL", "bash");
        String text = ShellEnv.format(changes, shell);
        boolean pwsh = shell.equals("pwsh") || shell.equals("powershell");
        Files.writeString(Path.of(envFile), pwsh ? "\uFEFF" + text : text, StandardCharsets.UTF_8);
    }

    /**
     * Writes a project file all at once (644 when new, whatever the umask), never through a link:
     * one put there since {@link #target} checked is replaced, and what it points at left alone.
     */
    static void write(Path file, String text) throws IOException {
        AtomicFile.write(file, text, StandardCharsets.UTF_8, false);
    }

    private static Path targetName(Context ctx, Path cwd, boolean here, String retry) {
        if (here) {
            Path hidden = cwd.resolve(ProjectFile.HIDDEN_TOML);
            return !Files.isRegularFile(cwd.resolve(ProjectFile.JKIT_TOML)) && Files.isRegularFile(hidden)
                    ? hidden : cwd.resolve(ProjectFile.JKIT_TOML);
        }
        String named = ctx.env.get(ProjectFile.OVERRIDE);
        boolean absentOverride = named != null && !named.isBlank() && !Files.exists(
                cwd.resolve(named).toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS);
        // an absolute override that doesn't exist yet is an error for reading, but it's the file to create
        Optional<ProjectSpec> found = absentOverride ? Optional.empty() : ProjectFile.find(cwd, ctx.env);
        if (found.isEmpty()) {
            String override = ctx.env.get(ProjectFile.OVERRIDE);
            if (override != null && override.isBlank()) {
                throw new JkitException(ProjectFile.OVERRIDE + " is set but empty, which turns project files off",
                        "unset it, or write to this folder's jkit.toml: " + retry + " --here");
            }
            if (override != null) {
                // the override names the file jkit reads, so that is the one to create, not ./jkit.toml
                Path file = cwd.resolve(override).toAbsolutePath().normalize();
                if (!file.getFileName().toString().endsWith(".toml")) {
                    throw new JkitException(ProjectFile.OVERRIDE + " names " + Changes.tilde(file.toString())
                            + ", which doesn't exist, and jkit only creates jkit.toml files",
                            "point " + ProjectFile.OVERRIDE + " at a .toml file, or unset it and run: " + retry);
                }
                if (!Files.isDirectory(file.getParent())) {
                    throw new JkitException(ProjectFile.OVERRIDE + " names " + Changes.tilde(file.toString())
                            + ", but its folder doesn't exist", "create the folder, then run: " + retry);
                }
                return file;
            }
            return cwd.resolve(ProjectFile.JKIT_TOML);
        }
        Path file = found.get().files().getFirst();
        String name = file.getFileName().toString();
        if (found.get().files().size() > 1 || name.equals(ProjectFile.SDKMANRC) || name.equals(ProjectFile.TOOL_VERSIONS)
                || name.equals(ProjectFile.JAVA_VERSION)) {
            String files = found.get().source();
            String override = ctx.env.get(ProjectFile.OVERRIDE);
            if (override != null && !override.isBlank()) {
                // the override wins over any jkit.toml, so --here wouldn't change what applies
                throw new JkitException(ProjectFile.OVERRIDE + " names " + Changes.tilde(file.toString())
                        + ", which jkit reads but doesn't write",
                        "point " + ProjectFile.OVERRIDE + " at a jkit.toml, or unset it and run: " + retry + " --here");
            }
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
        Plan plan = plan(ctx, dir);
        plan.notes().forEach(report::note);
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            if (plan.applies()) {
                report.note("the jkit shell hook isn't active, so this shell is unchanged; "
                        + "run one command with these versions: jkit exec -- <command>, or eval \"$(jkit env)\"");
            }
            return;
        }
        writeEnvFile(ctx, envFile, plan.changes());
        report.env(plan.changes());
        // not in a shell whose hook can't switch on cd: turning auto_switch on there only adds a warning
        if (plan.applies() && !ctx.config.autoSwitch() && Changes.shellWithoutAutoSwitch(ctx) == null) {
            report.note("to switch to a project's versions on cd: add auto_switch = true under [shell] in "
                    + Changes.tilde(ctx.home.config().toString()) + ", then open a new shell");
        }
    }

    /**
     * What applying the project found from {@code dir} changes in this shell.
     *
     * @param project  the project, or empty when there is none here
     * @param applied  the tools it sets, with their installs (a shell choice still wins for *_HOME)
     * @param missing  true when a pin has no matching install
     * @param changes  variables to set (value) or unset (null), including {@link ShellEnv#PROJECT}
     */
    record Plan(Optional<ProjectSpec> project, Map<Tool, Installed> applied, boolean missing,
                Map<String, String> changes, List<String> notes) {
        boolean applies() {
            return !applied.isEmpty();
        }
    }

    /** Suffix of {@link ShellEnv#PROJECT} when a pin wasn't installed, so the cd hook looks again. */
    static final String MISSING = ";missing";

    static Plan plan(Context ctx, Path dir) throws Exception {
        ToolStates.Result states = ToolStates.read(ctx.env, dir, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
        if (states.projectError() != null) {
            throw new JkitException(states.projectError());
        }
        String file = states.project().map(ProjectSpec::source).orElse("the project file");
        Map<Tool, Installed> applied = new EnumMap<>(Tool.class);
        Map<Tool, Path> homes = new EnumMap<>(Tool.class);
        List<String> notes = new ArrayList<>();
        boolean missing = false;
        for (ToolState s : states.tools()) {
            if (s.pin() == null) {
                continue;
            }
            if (s.pinInstall() == null) {
                notes.add(file + " wants " + StatusCommand.pinText(s.pin()) + ", not installed: jkit sync");
                missing = true;
                continue;
            }
            applied.put(s.tool(), s.pinInstall());
            homes.put(s.tool(), s.pinInstall().home());
            if (s.shellChoice() != null && !Fs.same(s.shellChoice(), s.pinInstall().home())) {
                notes.add("this shell chose its own " + s.tool() + " with jkit use, which wins over " + file
                        + ": jkit reset " + s.tool() + " to follow " + file);
            }
        }
        String key = states.project().isEmpty() ? null
                : ProjectFile.key(states.project().get().files()) + (missing ? MISSING : "");
        Map<String, String> changes = ShellEnv.project(ctx.env, ctx.home, homes,
                EnvCommand.isGraalVm(ctx, homes.get(Tool.JDK)), key);
        return new Plan(states.project(), applied, missing, changes, notes);
    }

    /** The install whose home is {@code home} (following links), or null. */
    static Installed installAt(Context ctx, Tool tool, Path home) throws IOException {
        if (home == null) {
            return null;
        }
        for (Installed i : ctx.store.list(tool.storeTool())) {
            if (Fs.same(i.home(), home)) {
                return i;
            }
        }
        return null;
    }
}
