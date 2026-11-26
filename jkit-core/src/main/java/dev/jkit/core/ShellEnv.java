package dev.jkit.core;

import dev.jkit.model.Envs;
import dev.jkit.model.ShellQuote;
import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the variables that {@code jkit use}, {@code jkit reset} and {@code jkit sync} change in
 * the current shell; the shell hook sources the result.
 *
 * <p>jkit remembers a {@code jkit use} choice in {@code JKIT_SHELL_<TOOL>} and an install applied
 * from a project file in {@code JKIT_PROJECT_<TOOL>}. A shell choice wins over a project's. When
 * jkit first replaces a *_HOME that the user set themselves, it saves the old value in
 * {@code JKIT_USER_<TOOL>}, and restores it (with its bin first on PATH) once no jkit choice
 * applies any more. jkit only removes PATH entries under tools/ and the global bin folders, so
 * entries the user added are never lost.
 */
public final class ShellEnv {
    static final String GRAALVM_FOLLOWS = "JKIT_GRAALVM_FOLLOWS_JDK";
    /**
     * The project applied to this shell, as {@link ProjectFile#key}. The {@code cd} hook compares it
     * to tell that nothing changed without reading the project files again.
     */
    public static final String PROJECT = "JKIT_PROJECT";

    private ShellEnv() { }

    public static String stateVariable(Tool tool) {
        return "JKIT_SHELL_" + tool.name();
    }

    /**
     * The variable that saves a *_HOME the user set themselves (not the global link the hook sets)
     * when jkit first replaces it, so it can be restored when jkit's choice goes away.
     */
    public static String userVariable(Tool tool) {
        return "JKIT_USER_" + tool.name();
    }

    /** Where this shell remembers the install a project file chose for {@code tool}. */
    public static String projectVariable(Tool tool) {
        return "JKIT_PROJECT_" + tool.name();
    }

    /** Variables to set (value) or unset (null), in a stable order. */
    public static Map<String, String> use(Map<String, String> env, JkitHome home, Tool tool, Path toolHome,
                                          boolean jdkIsGraalVm) {
        Map<Tool, String> active = active(env);
        active.put(tool, toolHome.toString());
        Map<String, String> out = new LinkedHashMap<>();
        out.put(stateVariable(tool), toolHome.toString());
        setHome(env, home, out, tool, toolHome.toString());
        if (tool == Tool.JDK && !active.containsKey(Tool.GRAALVM)) {
            if (jdkIsGraalVm) {
                setHome(env, home, out, Tool.GRAALVM, toolHome.toString());
                out.put(GRAALVM_FOLLOWS, "1");
            } else if (env.containsKey(GRAALVM_FOLLOWS)) {
                back(env, home, out, Tool.GRAALVM);
                out.put(GRAALVM_FOLLOWS, null);
            }
        }
        if (tool == Tool.GRAALVM) {
            out.put(GRAALVM_FOLLOWS, null);
        }
        out.put("PATH", path(env, home, active, out));
        return out;
    }

    /** Drops the shell choice: the project's install applies again if there is one, else the global one. */
    public static Map<String, String> reset(Map<String, String> env, JkitHome home, Tool tool) {
        return reset(env, home, tool, false);
    }

    /**
     * Like {@link #reset(Map, JkitHome, Tool)}; {@code projectJdkIsGraalVm} says whether the JDK the
     * project applied is GraalVM-based, so GRAALVM_HOME follows it again after {@code reset jdk}.
     */
    public static Map<String, String> reset(Map<String, String> env, JkitHome home, Tool tool,
                                            boolean projectJdkIsGraalVm) {
        Map<String, String> after = Envs.copy(env);
        after.remove(stateVariable(tool));
        Map<Tool, String> active = active(after);
        String project = blankToNull(env.get(projectVariable(tool)));
        Map<String, String> out = new LinkedHashMap<>();
        out.put(stateVariable(tool), null);
        if (project != null) {
            setHome(env, home, out, tool, project);
        } else {
            back(env, home, out, tool);
        }
        if (tool == Tool.JDK && !active.containsKey(Tool.GRAALVM)) {
            if (project != null && projectJdkIsGraalVm) {
                setHome(env, home, out, Tool.GRAALVM, project);
                out.put(GRAALVM_FOLLOWS, "1");
            } else if (env.containsKey(GRAALVM_FOLLOWS)) {
                back(env, home, out, Tool.GRAALVM);
                out.put(GRAALVM_FOLLOWS, null);
            }
        }
        out.put("PATH", path(env, home, active, out));
        return out;
    }

    /**
     * Applies a project's installs to this shell ({@code jkit sync}, {@code jkit pin}): each tool in
     * {@code homes} gets its *_HOME and bin folder unless the shell chose its own with
     * {@code jkit use}; a tool the project applied before but no longer pins goes back to global.
     * An empty map takes the project layer away completely.
     */
    public static Map<String, String> project(Map<String, String> env, JkitHome home, Map<Tool, Path> homes,
                                              boolean jdkIsGraalVm) {
        return project(env, home, homes, jdkIsGraalVm, false, null);
    }

    /**
     * Like {@link #project(Map, JkitHome, Map, boolean)}, also recording which project was applied in
     * {@link #PROJECT}: {@code projectKey}, or unset when null (no project here any more).
     */
    public static Map<String, String> project(Map<String, String> env, JkitHome home, Map<Tool, Path> homes,
                                              boolean jdkIsGraalVm, String projectKey) {
        return project(env, home, homes, jdkIsGraalVm, true, projectKey);
    }

    private static Map<String, String> project(Map<String, String> env, JkitHome home, Map<Tool, Path> homes,
                                               boolean jdkIsGraalVm, boolean recordKey, String projectKey) {
        Map<String, String> out = new LinkedHashMap<>();
        if (recordKey && (projectKey != null || env.containsKey(PROJECT))) {
            out.put(PROJECT, projectKey);
        }
        Map<String, String> after = Envs.copy(env);
        for (Tool t : Tool.values()) {
            boolean shellChoice = blankToNull(env.get(stateVariable(t))) != null;
            if (homes.containsKey(t)) {
                String value = homes.get(t).toString();
                out.put(projectVariable(t), value);
                after.put(projectVariable(t), value);
                if (!shellChoice) {
                    setHome(env, home, out, t, value);
                }
            } else if (env.containsKey(projectVariable(t))) {
                out.put(projectVariable(t), null);
                after.remove(projectVariable(t));
                if (!shellChoice) {
                    back(env, home, out, t);
                }
            }
        }
        boolean shellJdk = blankToNull(env.get(stateVariable(Tool.JDK))) != null;
        boolean shellGraalVm = blankToNull(env.get(stateVariable(Tool.GRAALVM))) != null;
        if (!shellGraalVm && !shellJdk) {
            if (homes.containsKey(Tool.GRAALVM)) {
                if (env.containsKey(GRAALVM_FOLLOWS)) {
                    out.put(GRAALVM_FOLLOWS, null);
                }
            } else if (homes.containsKey(Tool.JDK) && jdkIsGraalVm) {
                setHome(env, home, out, Tool.GRAALVM, homes.get(Tool.JDK).toString());
                out.put(GRAALVM_FOLLOWS, "1");
            } else if (env.containsKey(GRAALVM_FOLLOWS)) {
                back(env, home, out, Tool.GRAALVM);
                out.put(GRAALVM_FOLLOWS, null);
            }
        }
        out.put("PATH", path(env, home, active(after), out));
        return out;
    }

    /**
     * Variables for a new process (jkit env): each chosen tool's *_HOME, GRAALVM_HOME following a
     * GraalVM JDK when no GraalVM is chosen, and PATH with the chosen bin folders first. Unlike
     * {@link #use}, it records no shell state and leaves tools that aren't chosen alone.
     */
    public static Map<String, String> fresh(Map<String, String> env, JkitHome home, Map<Tool, Path> homes,
                                            boolean jdkIsGraalVm) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Tool t : Tool.values()) {
            if (homes.containsKey(t)) {
                out.put(t.homeVariable(), homes.get(t).toString());
            }
        }
        if (jdkIsGraalVm && homes.containsKey(Tool.JDK) && !homes.containsKey(Tool.GRAALVM)) {
            out.put(Tool.GRAALVM.homeVariable(), homes.get(Tool.JDK).toString());
        }
        List<String> entries = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (t.onPath() && homes.containsKey(t)) {
                entries.add(homes.get(t).resolve("bin").toString());
            }
        }
        for (String entry : userEntries(env)) {
            if (entry.isEmpty() || !underTools(entry, home) && !entries.contains(entry)) {
                entries.add(entry);
            }
        }
        out.put("PATH", String.join(File.pathSeparator, entries));
        return out;
    }

    /**
     * Points {@code tool}'s *_HOME at the global link and puts its bin first on PATH (unless it is
     * there already), as the hook does when it loads. Used after the first install in a shell
     * whose hook loaded before there was anything to link to.
     */
    public static Map<String, String> global(Map<String, String> env, JkitHome home, Tool tool) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(tool.homeVariable(), home.global(tool).toString());
        String bin = home.global(tool).resolve("bin").toString();
        if (tool.onPath() && !onPath(env, bin)) {
            String path = env.getOrDefault("PATH", "");
            out.put("PATH", path.isEmpty() ? bin : bin + File.pathSeparator + path);
        }
        return out;
    }

    /** Per tool, the install this shell has: its own choice, else the project's. */
    private static Map<Tool, String> active(Map<String, String> env) {
        Map<Tool, String> active = new EnumMap<>(Tool.class);
        for (Tool t : Tool.values()) {
            String value = blankToNull(env.get(stateVariable(t)));
            if (value == null) {
                value = blankToNull(env.get(projectVariable(t)));
            }
            if (value != null) {
                active.put(t, value);
            }
        }
        return active;
    }

    /**
     * Sets {@code tool}'s *_HOME to {@code value}. If the current value is the user's own (set
     * before any jkit choice, or changed by hand over one, and not the global link), it is saved
     * in {@link #userVariable} first so that {@link #back} can restore it.
     */
    private static void setHome(Map<String, String> env, JkitHome home, Map<String, String> out, Tool tool,
                                String value) {
        String current = blankToNull(env.get(tool.homeVariable()));
        boolean jkits = blankToNull(env.get(stateVariable(tool))) != null
                || blankToNull(env.get(projectVariable(tool))) != null
                || tool == Tool.GRAALVM && env.containsKey(GRAALVM_FOLLOWS)
                || env.containsKey(userVariable(tool));
        if (current != null && (!jkits || changedByHand(env, tool)) && !out.containsKey(userVariable(tool))
                && !isGlobal(current, home, tool)) {
            out.put(userVariable(tool), current);
        }
        out.put(tool.homeVariable(), value);
    }

    /**
     * The *_HOME jkit set for {@code tool} in this shell: the shell choice, else the project's, else
     * (for a GRAALVM_HOME that follows the JDK) the JDK's. Null when jkit set none.
     */
    private static String jkitsHome(Map<String, String> env, Tool tool) {
        String value = blankToNull(env.get(stateVariable(tool)));
        if (value == null) {
            value = blankToNull(env.get(projectVariable(tool)));
        }
        if (value == null && tool == Tool.GRAALVM && env.containsKey(GRAALVM_FOLLOWS)) {
            value = jkitsHome(env, Tool.JDK);
        }
        return value;
    }

    /**
     * Whether the user changed or unset {@code tool}'s *_HOME by hand since jkit set it. Such a value
     * belongs to the user from then on, and jkit leaves it alone when its own choice goes away.
     */
    public static boolean changedByHand(Map<String, String> env, Tool tool) {
        String jkits = jkitsHome(env, tool);
        if (jkits == null) {
            return false;
        }
        String current = blankToNull(env.get(tool.homeVariable()));
        return current == null || !samePathEntry(current, jkits);
    }

    /**
     * Takes jkit's choice for {@code tool} away: *_HOME goes back to the value {@link #setHome}
     * saved, else to the global link (or is unset when there is none). A *_HOME the user changed by
     * hand since jkit set it is kept as it is, and the saved value is dropped.
     */
    private static void back(Map<String, String> env, JkitHome home, Map<String, String> out, Tool tool) {
        if (changedByHand(env, tool)) {
            if (env.containsKey(userVariable(tool))) {
                out.put(userVariable(tool), null);
            }
            return;
        }
        String own = blankToNull(env.get(userVariable(tool)));
        if (own != null) {
            out.put(tool.homeVariable(), own);
            out.put(userVariable(tool), null);
        } else {
            if (env.containsKey(userVariable(tool))) {
                out.put(userVariable(tool), null);
            }
            out.put(tool.homeVariable(), globalOrNull(home, tool));
        }
    }

    /**
     * Whether jkit made a choice for {@code tool} in this shell (jkit use, a project, or a saved
     * user *_HOME), i.e. whether a reset has anything to undo.
     */
    public static boolean hasChoice(Map<String, String> env, Tool tool) {
        return blankToNull(env.get(stateVariable(tool))) != null || env.containsKey(projectVariable(tool))
                || env.containsKey(userVariable(tool));
    }

    /** Whether a *_HOME value is the global link, as the hook sets it. */
    public static boolean isGlobal(String value, JkitHome home, Tool tool) {
        Path path = Fs.path(value);
        return path != null && path.toAbsolutePath().normalize().equals(home.global(tool).toAbsolutePath().normalize());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String globalOrNull(JkitHome home, Tool tool) {
        Path link = home.global(tool);
        return Files.exists(link) ? link.toString() : null;
    }

    /**
     * PATH with each active install's bin first. For a tool without one, PATH follows the *_HOME
     * the shell ends up with ({@code changes} applied over {@code env}): a *_HOME of the user's own
     * puts its bin first in place of the global bin, so that {@code java} is $JAVA_HOME/bin/java,
     * and the global link puts the global bin back. Only entries under tools/ and the global bins
     * are removed; the user's own entries all stay.
     */
    static String path(Map<String, String> env, JkitHome home, Map<Tool, String> active, Map<String, String> changes) {
        List<String> entries = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (!t.onPath()) {
                continue;
            }
            String global = home.global(t).resolve("bin").toString();
            if (active.containsKey(t)) {
                entries.add(Path.of(active.get(t), "bin").toString());
                continue;
            }
            String toolHome = blankToNull(changes.containsKey(t.homeVariable())
                    ? changes.get(t.homeVariable()) : env.get(t.homeVariable()));
            // No jkit choice is left for this tool, so its *_HOME is either the user's own or the
            // global link; when a choice just went away, changes holds the restored value.
            if (toolHome == null) {
                continue;
            }
            Path ownBin = isGlobal(toolHome, home, t) ? null : Fs.path(toolHome);
            if (ownBin == null) {
                if (!onPath(env, global)) {
                    entries.add(global);
                }
            } else if (Files.isDirectory(ownBin.resolve("bin"))) {
                dropped.add(global);
                String bin = ownBin.resolve("bin").toString();
                // Entries under tools/ are all removed below, so a user's own *_HOME inside tools/
                // must be added here even if PATH already has it.
                if (!onPath(env, bin) || underTools(bin, home)) {
                    entries.add(bin);
                }
            }
        }
        for (String entry : userEntries(env)) {
            if (entry.isEmpty() || !underTools(entry, home) && dropped.stream().noneMatch(d -> samePathEntry(entry, d))) {
                entries.add(entry);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * This shell's PATH entries in order, empty ones included: an empty entry means the current
     * folder, and it is the user's to keep or drop, not jkit's. None for an empty PATH.
     */
    private static List<String> userEntries(Map<String, String> env) {
        String current = env.getOrDefault("PATH", "");
        return current.isEmpty() ? List.of() : List.of(current.split(File.pathSeparator, -1));
    }

    private static boolean onPath(Map<String, String> env, String entry) {
        return Arrays.stream(env.getOrDefault("PATH", "").split(File.pathSeparator))
                .anyMatch(e -> !e.isEmpty() && samePathEntry(e, entry));
    }

    /**
     * Whether two PATH entries name the same folder, compared as absolute normalized paths: a
     * trailing separator or {@code \h\x} versus {@code C:\h\x} makes no difference, and on Windows
     * neither do case or slash direction. Symbolic links aren't followed.
     */
    static boolean samePathEntry(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Whether a PATH entry is inside tools/, compared folder by folder (so tools-old/ isn't), and
     * on Windows without regard to case or slash direction.
     */
    static boolean underTools(String entry, JkitHome home) {
        try {
            return Path.of(entry).toAbsolutePath().normalize().startsWith(home.toolsRoot());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Renders changes for a shell: bash/zsh, fish or pwsh. */
    public static String format(Map<String, String> changes, String shell) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            switch (shell) {
                case "fish" -> sb.append(v == null ? "set -e " + k : "set -gx " + k + " " + quote(shell, v)).append('\n');
                case "pwsh", "powershell" -> sb.append(v == null
                        ? "Remove-Item Env:" + k + " -ErrorAction SilentlyContinue"
                        : "$env:" + k + " = " + quote(shell, v)).append('\n');
                default -> sb.append(v == null ? "unset " + k : "export " + k + "=" + quote(shell, v)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String quote(String shell, String value) {
        return ShellQuote.quote(shell, value);
    }
}
