package dev.jkit.cli;

import dev.jkit.core.JkitHome;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.Tool;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a command changed outside the store: variables of the calling shell and global links, plus
 * what that means for this shell and new ones. Printed as indented lines under the command's own
 * message, and in JSON as {@code "changes"}: a list of {@code {kind, name, action, value}}.
 */
final class Changes {
    /** {@code kind}: env or link. {@code action}: set, unset, add, remove (PATH entries). */
    record Change(String kind, String name, String action, String value) { }

    private final Context ctx;
    private final List<Change> changes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    Changes(Context ctx) {
        this.ctx = ctx;
    }

    /** Records the shell variables in {@code updates} (null = unset) that differ from this shell's. */
    Changes env(Map<String, String> updates) {
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String name = e.getKey();
            String before = ctx.env.get(name);
            String after = e.getValue();
            if (name.equals("PATH")) {
                path(before, after);
            } else if (after == null && before != null) {
                changes.add(new Change("env", name, "unset", null));
            } else if (after != null && !after.equals(before)) {
                changes.add(new Change("env", name, "set", after));
            }
        }
        return this;
    }

    private void path(String before, String after) {
        Set<String> old = entries(before);
        Set<String> now = entries(after);
        for (String entry : now) {
            if (!old.contains(entry)) {
                changes.add(new Change("env", "PATH", "add", entry));
            }
        }
        for (String entry : old) {
            if (!now.contains(entry)) {
                changes.add(new Change("env", "PATH", "remove", entry));
            }
        }
    }

    private static Set<String> entries(String path) {
        Set<String> set = new LinkedHashSet<>(Arrays.asList((path == null ? "" : path).split(File.pathSeparator)));
        set.remove("");
        return set;
    }

    /** A global link now points at {@code target}, or was removed (null). */
    Changes link(Path link, Path target) {
        changes.add(target == null
                ? new Change("link", link.toAbsolutePath().toString(), "remove", null)
                : new Change("link", link.toAbsolutePath().toString(), "set", target.toAbsolutePath().toString()));
        return this;
    }

    Changes note(String note) {
        notes.add(note);
        return this;
    }

    /**
     * After the global link for {@code tool} moved: which variables follow it in new shells, and
     * whether this shell picks it up, keeps its own choice, or is overridden by a variable set
     * outside jkit.
     */
    Changes globalEffect(Tool tool) {
        JkitHome home = ctx.home;
        String link = tilde(home.global(tool));
        String var = tool.homeVariable();
        note("new shells with the jkit hook: " + var + "=" + link
                + (tool.onPath() ? ", PATH includes " + link + File.separator + "bin" : ""));
        String shellChoice = ctx.env.get(ShellEnv.stateVariable(tool));
        String current = ctx.env.get(var);
        if (shellChoice != null && !shellChoice.isBlank()) {
            note("this shell keeps its own " + tool + " (" + tilde(Path.of(shellChoice))
                    + "); to follow the global one: jkit reset " + tool);
        } else if (current != null && Path.of(current).toAbsolutePath().normalize()
                .equals(home.global(tool).toAbsolutePath().normalize())) {
            note("this shell follows it already: " + var + "=" + link);
        } else if (current != null && !current.isBlank()) {
            note("warning: " + var + "=" + current + " is set outside jkit and wins over the global " + tool
                    + "; remove it from your shell profile to use jkit's");
        } else if (ctx.env.get("JKIT_SHELL") == null) {
            note("the jkit hook is not loaded in this shell, so nothing sets " + var
                    + "; add to ~/.bashrc:  eval \"$(jkit hook bash)\"");
        } else {
            note(var + " is not set in this shell yet; open a new shell, or: jkit use " + tool);
        }
        return this;
    }

    boolean isEmpty() {
        return changes.isEmpty();
    }

    /** Indented lines: one per change, then the notes. */
    void print(PrintStream out) {
        for (Change c : changes) {
            out.println("  " + switch (c.kind() + " " + c.action()) {
                case "env set" -> "set    " + c.name() + "=" + tilde(c.value());
                case "env unset" -> "unset  " + c.name();
                case "env add" -> "PATH   + " + tilde(c.value());
                case "env remove" -> "PATH   - " + tilde(c.value());
                case "link set" -> "link   " + tilde(c.name()) + " → " + tilde(c.value());
                case "link remove" -> "link   " + tilde(c.name()) + " removed";
                default -> c.toString();
            });
        }
        for (String note : notes) {
            out.println("  " + note);
        }
    }

    List<Object> json() {
        List<Object> list = new ArrayList<>();
        for (Change c : changes) {
            list.add(JsonOut.fields("kind", c.kind(), "name", c.name(), "action", c.action(), "value", c.value()));
        }
        return list;
    }

    List<String> notes() {
        return List.copyOf(notes);
    }

    private static String tilde(Path path) {
        return tilde(path.toAbsolutePath().toString());
    }

    /** Shortens the home folder to ~ for display. */
    static String tilde(String text) {
        String userHome = System.getProperty("user.home");
        if (text == null || userHome == null || userHome.isEmpty()) {
            return text;
        }
        return Objects.equals(text, userHome) ? "~"
                : text.startsWith(userHome + File.separator) ? "~" + text.substring(userHome.length()) : text;
    }
}
