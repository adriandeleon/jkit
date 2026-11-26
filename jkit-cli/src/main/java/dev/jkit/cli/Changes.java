package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.JkitHome;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ShellRc;
import dev.jkit.model.Envs;
import dev.jkit.model.Tool;
import dev.jkit.model.Visible;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything a command changed: variables of the calling shell, global links, folders created or
 * deleted and files written, plus notes on what that means for this shell and new ones. Every
 * command that changes something reports it here, so that nothing changes silently. Printed as
 * indented lines under the command's own message, and in JSON as {@code "changes"}, a list of
 * {@code {kind, name, action, value}}.
 */
final class Changes {
    /**
     * One change. The {@code kind}s and their {@code action}s:
     * <ul>
     *   <li>env: set, unset, or add/remove for PATH entries;</li>
     *   <li>user-env: the same, for the user's persistent environment variables on Windows;</li>
     *   <li>link: set or remove;</li>
     *   <li>folder: create or delete;</li>
     *   <li>file: create, update or delete. For a cached version list or checksum, {@code value} is
     *       the URL its content came from; otherwise (a project file or shell startup file jkit
     *       wrote, a deleted file) it is null.</li>
     * </ul>
     */
    record Change(String kind, String name, String action, String value) { }

    private final Context ctx;
    private final List<Change> changes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** A folder the command's own message names; "created" lines for it and its parents are skipped. */
    private Path named;
    /** Whether to skip the line about cached version lists and checksums. */
    private boolean quietCache;

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
        // When jkit saves or restores a *_HOME the user set themselves, say so.
        for (Tool t : Tool.values()) {
            String kept = ShellEnv.userVariable(t);
            if (!updates.containsKey(kept)) {
                continue;
            }
            String saved = updates.get(kept);
            String was = ctx.env.get(kept);
            if (saved != null && !saved.equals(was)) {
                note("your own " + t.homeVariable() + " is kept in " + kept + ": it comes back when jkit's "
                        + t + " leaves this shell");
            } else if (saved == null && was != null && Objects.equals(updates.get(t.homeVariable()), was)) {
                note(t.homeVariable() + " is your own again: " + tilde(was));
            }
        }
        // When jkit's choice goes away but the user changed *_HOME by hand after jkit set it, jkit
        // leaves that value in place: say so, since it may surprise.
        Map<String, String> after = Envs.copy(ctx.env);
        updates.forEach((k, v) -> {
            if (v == null) {
                after.remove(k);
            } else {
                after.put(k, v);
            }
        });
        for (Tool t : Tool.values()) {
            if (!updates.containsKey(t.homeVariable()) && ShellEnv.changedByHand(ctx.env, t)
                    && !ShellEnv.hasChoice(after, t)) {
                String current = ctx.env.get(t.homeVariable());
                note(current == null || current.isBlank()
                        ? t.homeVariable() + " stays unset: you unset it after jkit set it"
                        : t.homeVariable() + " stays as you set it after jkit did: " + tilde(current));
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

    /** A folder this command created (an install) or deleted (remove, leftovers). */
    Changes folder(Path folder, boolean created) {
        changes.add(new Change("folder", folder.toAbsolutePath().toString(), created ? "create" : "delete", null));
        return this;
    }

    /**
     * Adds what the command wrote to disk along the way: the jkit home if it created it, and the
     * version lists and checksums the cache stored. Each is reported once, by whichever Changes
     * asks first.
     */
    Changes disk() {
        if (!ctx.homeExisted && !ctx.homeReported && Files.exists(ctx.home.root())) {
            changes.add(new Change("folder", ctx.home.root().toAbsolutePath().toString(), "create", null));
            ctx.homeReported = true;
        }
        for (Context.CacheWrite w : ctx.cacheWrites) {
            changes.add(w.source() == null
                    ? new Change("file", w.file().toAbsolutePath().toString(), "delete", null)
                    : new Change("file", w.file().toAbsolutePath().toString(), w.created() ? "create" : "update",
                            w.source().toString()));
        }
        ctx.cacheWrites.clear();
        return this;
    }

    /**
     * A variable in the user's persistent environment (Windows, jkit env --install); {@code action}
     * is as for env.
     */
    Changes userEnv(String name, String action, String value) {
        changes.add(new Change("user-env", name, action, value));
        return this;
    }

    /** A file this command deleted (jkit hook fish --uninstall). */
    Changes fileDeleted(Path file) {
        changes.add(new Change("file", file.toAbsolutePath().toString(), "delete", null));
        return this;
    }

    /**
     * How to load the hook in new shells. On Windows it is the line to add to the PowerShell
     * profile by hand, since hook --install doesn't edit PowerShell profiles.
     */
    static final String HOOK_SETUP = File.separatorChar == '\\'
            ? "add to your PowerShell $PROFILE: jkit hook pwsh | Out-String | Invoke-Expression"
            : "jkit hook --install";

    /**
     * What to tell a user whose shell doesn't have the hook loaded. If the login shell's startup
     * file already loads it (jkit hook --install ran, but this shell started before), opening a new
     * shell is enough; otherwise they need to set it up.
     */
    static String hookHint(Context ctx) {
        Path file = hookFile(ctx);
        return file == null ? "load it in new shells: " + HOOK_SETUP
                : tilde(file) + " loads it, so open a new shell";
    }

    /** The login shell's startup file if it loads the hook; null if it doesn't or that can't be told. */
    private static Path hookFile(Context ctx) {
        if (ctx.platform.isWindows()) {
            return null; // jkit doesn't edit or inspect PowerShell profiles
        }
        try {
            Path file = ShellRc.file(HookCommand.loginShell(ctx), ctx.env, HookCommand.userHome(ctx));
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String text = Files.readString(file, HookCommand.charsetOf(file));
            return ShellRc.hasBlock(text) || ShellRc.manualLine(text) > 0 ? file : null;
        } catch (IOException | RuntimeException e) { // including JkitException: no login shell or home
            return null;
        }
    }

    /**
     * The name of the shell jkit runs in, for messages, when that shell's hook has no support for
     * switching versions on cd (shell.auto_switch). Null when it has, or when the shell isn't
     * known. On Windows without the hook loaded, the shell is assumed to be PowerShell, the only
     * shell jkit has a hook for there.
     */
    static String shellWithoutAutoSwitch(Context ctx) {
        String shell = ctx.env.get("JKIT_SHELL");
        if (shell == null || shell.isBlank()) {
            shell = ctx.platform.isWindows() ? "pwsh" : null;
        }
        if (shell == null || ctx.os.autoSwitchHook(shell) != null) {
            return null;
        }
        return shell.equals("pwsh") ? "PowerShell" : shell;
    }

    /** A project file or shell startup file this command wrote (pin, unpin, hook --install). */
    Changes file(Path file, boolean created) {
        changes.add(new Change("file", file.toAbsolutePath().toString(), created ? "create" : "update", null));
        return this;
    }

    /**
     * For install, whose own message already names the new folder: skips the "created" lines for
     * that folder and its parents (the jkit home, tools/), and the line about cached version lists.
     * --json still lists them all.
     */
    Changes namedInMessage(Path folder) {
        named = folder == null ? null : folder.toAbsolutePath().normalize();
        quietCache = true;
        return this;
    }

    Changes note(String note) {
        notes.add(note);
        return this;
    }

    /**
     * Notes what moving the global link for {@code tool} means: which variables follow it in new
     * shells, and whether this shell picks it up, keeps its own choice, or is overridden by a
     * variable set outside jkit.
     *
     * <p>In a hooked shell with no jkit choice for the tool and no *_HOME set, this also sets
     * *_HOME to the global link right away, through the hook's env file. The hook sets it only
     * when it loads, and after a first install there was nothing to link to at that time.
     */
    Changes globalEffect(Tool tool) throws IOException {
        JkitHome home = ctx.home;
        String link = tilde(home.global(tool));
        String var = tool.homeVariable();
        note("new shells with the jkit hook: " + var + "=" + link
                + (tool.onPath() ? ", PATH includes " + link + File.separator + "bin" : ""));
        Boolean follows = UserEnvSetup.follows(ctx, tool); // null: not Windows
        if (Boolean.TRUE.equals(follows)) {
            note("programs started outside PowerShell (cmd.exe, IDEs) follow it too: your user environment's "
                    + var + " names the link");
        } else if (Boolean.FALSE.equals(follows)) {
            note("for programs started outside PowerShell (cmd.exe, IDEs) too: jkit env --install");
        }
        String shellChoice = ctx.env.get(ShellEnv.stateVariable(tool));
        String projectChoice = ctx.env.get(ShellEnv.projectVariable(tool));
        String current = ctx.env.get(var);
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (shellChoice != null && !shellChoice.isBlank()) {
            note("this shell keeps its own " + tool + " (" + tilde(Path.of(shellChoice))
                    + "); to follow the global one: jkit reset " + tool);
        } else if (projectChoice != null && !projectChoice.isBlank() && current != null && !current.isBlank()) {
            note("this shell keeps the project's " + tool + " (" + tilde(Path.of(projectChoice))
                    + ") while it is in the project");
        } else if (Fs.path(current) != null && Fs.path(current).toAbsolutePath().normalize()
                .equals(home.global(tool).toAbsolutePath().normalize())) {
            note("this shell follows it already: " + var + "=" + link);
        } else if (current != null && !current.isBlank()) {
            note("warning: " + var + "=" + current + " is set outside jkit and wins over the global " + tool
                    + "; remove it from your shell profile to use jkit's");
        } else if (ctx.env.get("JKIT_SHELL") == null) {
            note("the jkit hook is not loaded in this shell, so nothing sets " + var + "; " + hookHint(ctx));
        } else if (envFile != null && !ShellEnv.hasChoice(ctx.env, tool)) {
            Map<String, String> updates = ShellEnv.global(ctx.env, home, tool);
            ProjectShell.writeEnvFile(ctx, envFile, updates);
            env(updates);
        } else {
            note(var + " is not set in this shell; open a new shell, or: eval \"$(jkit env)\"");
        }
        return this;
    }

    /**
     * Whether nothing that other programs read changed. jkit's own JKIT_* variables don't count
     * here, though they are still printed like every other change to the shell.
     */
    boolean isEmpty() {
        return changes.stream().allMatch(Changes::bookkeeping);
    }

    /** Whether a change is to jkit's own bookkeeping variables (JKIT_PROJECT, JKIT_SHELL_JDK...). */
    private static boolean bookkeeping(Change c) {
        return c.kind().equals("env") && c.name().startsWith("JKIT_");
    }

    /** Indented lines: one per change (cached files folded into one line), then the notes. */
    void print(PrintStream out) {
        int listsCreated = 0;
        int listsUpdated = 0;
        int listsDeleted = 0;
        Set<String> hosts = new LinkedHashSet<>();
        Path index = ctx.home.index().toAbsolutePath();
        for (Change c : changes) {
            if (named != null && c.kind().equals("folder") && c.action().equals("create")
                    && named.startsWith(Path.of(c.name()).normalize())) {
                continue; // the message names this folder, or one inside it
            }
            if (quietCache && c.kind().equals("file") && Path.of(c.name()).startsWith(index)) {
                continue;
            }
            if (c.kind().equals("file") && c.action().equals("delete") && Path.of(c.name()).startsWith(index)) {
                listsDeleted++; // old cached lookups, folded into the cache line
                continue;
            }
            if (c.kind().equals("file") && c.value() != null) {
                if (c.action().equals("create")) {
                    listsCreated++;
                } else {
                    listsUpdated++;
                }
                hosts.add(URI.create(c.value()).getHost());
                continue;
            }
            out.println("  " + switch (c.kind() + " " + c.action()) {
                case "env set" -> "set    " + c.name() + "=" + tilde(c.value());
                case "env unset" -> "unset  " + c.name();
                case "env add" -> "PATH   + " + tilde(c.value());
                case "env remove" -> "PATH   - " + tilde(c.value());
                case "user-env set" -> "user   " + c.name() + "=" + tilde(c.value());
                case "user-env unset" -> "user   " + c.name() + " removed";
                case "user-env add" -> "user   PATH + " + tilde(c.value());
                case "user-env remove" -> "user   PATH - " + tilde(c.value());
                case "link set" -> "link   " + tilde(c.name()) + " → " + tilde(c.value());
                case "link remove" -> "link   " + tilde(c.name()) + " removed";
                case "folder create" -> "folder " + tilde(c.name()) + " created";
                case "folder delete" -> "folder " + tilde(c.name()) + " deleted";
                case "file create" -> "file   " + tilde(c.name()) + " created";
                case "file update" -> "file   " + tilde(c.name()) + " updated";
                case "file delete" -> "file   " + tilde(c.name()) + " deleted";
                default -> c.toString();
            });
        }
        if (listsCreated + listsUpdated + listsDeleted > 0) {
            List<String> parts = new ArrayList<>();
            if (listsCreated > 0) {
                parts.add(listsCreated + (listsCreated == 1 ? " file" : " files") + " saved");
            }
            if (listsUpdated > 0) {
                parts.add(listsUpdated + (listsUpdated == 1 ? " file" : " files") + " refreshed");
            }
            String text = parts.isEmpty() ? "" : String.join(", ", parts) + " from " + String.join(", ", hosts);
            if (listsDeleted > 0) {
                text += (text.isEmpty() ? "" : "; ") + listsDeleted + (listsDeleted == 1 ? " old file" : " old files")
                        + " removed";
            }
            out.println("  cache  " + tilde(index.toString()) + ": " + text);
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
        text = Visible.of(text); // a folder name can contain a newline or an RTL override character
        String userHome = System.getProperty("user.home");
        if (text == null || userHome == null || userHome.isEmpty()) {
            return text;
        }
        return Objects.equals(text, userHome) ? "~"
                : text.startsWith(userHome + File.separator) ? "~" + text.substring(userHome.length()) : text;
    }
}
