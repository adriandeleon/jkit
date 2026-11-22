package dev.jkit.core;

import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the variables a shell-level {@code jkit use} or {@code jkit reset} changes. The shell
 * wrapper sources the result. jkit remembers each shell choice in {@code JKIT_SHELL_<TOOL>} and
 * only ever removes PATH entries under tools/, so it never touches anything the user added.
 */
public final class ShellEnv {
    static final String GRAALVM_FOLLOWS = "JKIT_GRAALVM_FOLLOWS_JDK";

    private ShellEnv() { }

    public static String stateVariable(Tool tool) {
        return "JKIT_SHELL_" + tool.name();
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

    public static Map<String, String> reset(Map<String, String> env, JkitHome home, Tool tool) {
        Map<Tool, String> active = active(env);
        active.remove(tool);
        Map<String, String> out = new LinkedHashMap<>();
        out.put(stateVariable(tool), null);
        out.put(tool.homeVariable(), globalOrNull(home, tool));
        if (tool == Tool.JDK && env.containsKey(GRAALVM_FOLLOWS)) {
            out.put(Tool.GRAALVM.homeVariable(), globalOrNull(home, Tool.GRAALVM));
            out.put(GRAALVM_FOLLOWS, null);
        }
        out.put("PATH", path(env, home, active));
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

    private static Map<Tool, String> active(Map<String, String> env) {
        Map<Tool, String> active = new EnumMap<>(Tool.class);
        for (Tool t : Tool.values()) {
            String value = env.get(stateVariable(t));
            if (value != null && !value.isBlank()) {
                active.put(t, value);
            }
        }
        return active;
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
