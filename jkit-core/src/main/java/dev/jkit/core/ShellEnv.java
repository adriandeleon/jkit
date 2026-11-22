package dev.jkit.core;

import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the variables a shell-level {@code jkit use}, {@code jkit reset} or {@code jkit sync}
 * changes. The shell wrapper sources the result. jkit remembers each shell choice in
 * {@code JKIT_SHELL_<TOOL>} and each install applied from a project file in
 * {@code JKIT_PROJECT_<TOOL>}; a shell choice wins over a project's. It only ever removes PATH
 * entries under tools/, so it never touches anything the user added.
 */
public final class ShellEnv {
    static final String GRAALVM_FOLLOWS = "JKIT_GRAALVM_FOLLOWS_JDK";
    /**
     * The project applied to this shell, as {@link ProjectFile#key}: lets the {@code cd} hook see
     * that nothing changed without reading the files again.
     */
    public static final String PROJECT = "JKIT_PROJECT";

    private ShellEnv() { }

    public static String stateVariable(Tool tool) {
        return "JKIT_SHELL_" + tool.name();
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
        out.put(tool.homeVariable(), toolHome.toString());
        if (tool == Tool.JDK && !active.containsKey(Tool.GRAALVM)) {
            if (jdkIsGraalVm) {
                out.put(Tool.GRAALVM.homeVariable(), toolHome.toString());
                out.put(GRAALVM_FOLLOWS, "1");
            } else if (env.containsKey(GRAALVM_FOLLOWS)) {
                out.put(Tool.GRAALVM.homeVariable(), globalOrNull(home, Tool.GRAALVM));
                out.put(GRAALVM_FOLLOWS, null);
            }
        }
        if (tool == Tool.GRAALVM) {
            out.put(GRAALVM_FOLLOWS, null);
        }
        out.put("PATH", path(env, home, active));
        return out;
    }

    /** Drops the shell choice: the project's install applies again if one was applied, else the global one. */
    public static Map<String, String> reset(Map<String, String> env, JkitHome home, Tool tool) {
        return reset(env, home, tool, false);
    }

    /**
     * Like {@link #reset(Map, JkitHome, Tool)}; {@code projectJdkIsGraalVm} says whether the JDK the
     * project applied is GraalVM-based, so GRAALVM_HOME follows it again after {@code reset jdk}.
     */
    public static Map<String, String> reset(Map<String, String> env, JkitHome home, Tool tool,
                                            boolean projectJdkIsGraalVm) {
        Map<String, String> after = new HashMap<>(env);
        after.remove(stateVariable(tool));
        Map<Tool, String> active = active(after);
        String project = blankToNull(env.get(projectVariable(tool)));
        Map<String, String> out = new LinkedHashMap<>();
        out.put(stateVariable(tool), null);
        out.put(tool.homeVariable(), project != null ? project : globalOrNull(home, tool));
        if (tool == Tool.JDK && !active.containsKey(Tool.GRAALVM)) {
            if (project != null && projectJdkIsGraalVm) {
                out.put(Tool.GRAALVM.homeVariable(), project);
                out.put(GRAALVM_FOLLOWS, "1");
            } else if (env.containsKey(GRAALVM_FOLLOWS)) {
                out.put(Tool.GRAALVM.homeVariable(), globalOrNull(home, Tool.GRAALVM));
                out.put(GRAALVM_FOLLOWS, null);
            }
        }
        out.put("PATH", path(env, home, active));
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
        Map<String, String> after = new HashMap<>(env);
        for (Tool t : Tool.values()) {
            boolean shellChoice = blankToNull(env.get(stateVariable(t))) != null;
            if (homes.containsKey(t)) {
                String value = homes.get(t).toString();
                out.put(projectVariable(t), value);
                after.put(projectVariable(t), value);
                if (!shellChoice) {
                    out.put(t.homeVariable(), value);
                }
            } else if (env.containsKey(projectVariable(t))) {
                out.put(projectVariable(t), null);
                after.remove(projectVariable(t));
                if (!shellChoice) {
                    out.put(t.homeVariable(), globalOrNull(home, t));
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
                out.put(Tool.GRAALVM.homeVariable(), homes.get(Tool.JDK).toString());
                out.put(GRAALVM_FOLLOWS, "1");
            } else if (env.containsKey(GRAALVM_FOLLOWS)) {
                out.put(Tool.GRAALVM.homeVariable(), globalOrNull(home, Tool.GRAALVM));
                out.put(GRAALVM_FOLLOWS, null);
            }
        }
        out.put("PATH", path(env, home, active(after)));
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
        for (String entry : env.getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!entry.isEmpty() && !underTools(entry, home) && !entries.contains(entry)) {
                entries.add(entry);
            }
        }
        out.put("PATH", String.join(File.pathSeparator, entries));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String globalOrNull(JkitHome home, Tool tool) {
        Path link = home.global(tool);
        return Files.exists(link) ? link.toString() : null;
    }

    static String path(Map<String, String> env, JkitHome home, Map<Tool, String> active) {
        List<String> entries = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (t.onPath() && active.containsKey(t)) {
                entries.add(Path.of(active.get(t), "bin").toString());
            }
        }
        String current = env.getOrDefault("PATH", "");
        for (String entry : current.split(File.pathSeparator)) {
            if (!entry.isEmpty() && !underTools(entry, home)) {
                entries.add(entry);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Whether a PATH entry is inside tools/, compared folder by folder (so tools-old/ isn't), and
     * on Windows without regard to case or slash direction.
     */
    static boolean underTools(String entry, JkitHome home) {
        try {
            return Path.of(entry).toAbsolutePath().normalize().startsWith(home.toolsRoot());
        } catch (java.nio.file.InvalidPathException e) {
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
        return dev.jkit.model.ShellQuote.quote(shell, value);
    }
}
