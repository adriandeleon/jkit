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
 * Everything a command changed: variables of the calling shell, global links, folders it created
 * or deleted and files it wrote, plus what that means for this shell and new ones. Every command
 * that changes something reports it here, so nothing changes silently. Printed as indented lines
 * under the command's own message, and in JSON as {@code "changes"}: a list of
 * {@code {kind, name, action, value}}.
 */
final class Changes {
    /**
     * {@code kind} env: {@code action} set, unset, add or remove (PATH entries); user-env: the same,
     * for the user's own environment variables (Windows). link: set or remove.
     * folder: create or delete. file: a cached version list or checksum, create or update
     * ({@code value} is where its content came from), or a project file jkit wrote (no {@code value}).
     */
    record Change(String kind, String name, String action, String value) { }

    private final Context ctx;
    private final List<Change> changes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** A folder the command's own message names, with its parents: their "created" lines go unsaid. */
    private Path named;
    /** Whether the line for cached version lists and checksums goes unsaid. */
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
        // a *_HOME set outside jkit: say that jkit keeps it, and when it comes back
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
        // jkit's choice goes, but the user changed *_HOME by hand since jkit set it: it stays
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
     * What the command wrote to disk on the side: the jkit home if it made it, and the files (version
     * lists, checksums) the cache stored. Each is reported once, by whichever Changes asks first.
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
     * A variable in the user's own environment (Windows, jkit env --install): {@code action} set,
     * unset, add or remove (PATH entries), as for {@code env}.
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
     * How to load the hook in new shells: hook --install can't edit a PowerShell profile, so on
     * Windows the line to add by hand.
     */
    static final String HOOK_SETUP = File.separatorChar == '\\'
            ? "add to your PowerShell $PROFILE: jkit hook pwsh | Out-String | Invoke-Expression"
            : "jkit hook --install";

    /**
     * What to do in a shell without the hook: open a new shell when the login shell's startup file
     * already loads it (after jkit hook --install, until the shell is restarted), else load it
     * in new shells.
     */
    static String hookHint(Context ctx) {
        Path file = hookFile(ctx);
        return file == null ? "load it in new shells: " + HOOK_SETUP
                : tilde(file) + " loads it, so open a new shell";
    }

    /** The login shell's startup file when it loads the hook, else null (or when that can't be told). */
    private static Path hookFile(Context ctx) {
        if (ctx.platform.isWindows()) {
            return null; // a PowerShell profile is the user's to edit
        }
        try {
            Path file = ShellRc.file(HookCommand.loginShell(ctx), ctx.env, HookCommand.userHome(ctx));
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String text = Files.readString(file, HookCommand.charsetOf(file));
            return ShellRc.hasBlock(text) || ShellRc.manualLine(text) > 0 ? file : null;
        } catch (IOException | RuntimeException e) { // JkitException too: no login shell or home
            return null;
        }
    }

    /**
     * The shell jkit runs in, by name for messages, when its hook can't switch on cd yet
     * (shell.auto_switch); null when it can, or when the shell isn't known. Without a hook, a
     * Windows shell counts as PowerShell, the only hook jkit has there.
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
     * For install, whose message names the new folder: the folders it implies (that one, and the
     * jkit home or tools/ folders made for it) and the cached version lists aren't printed again.
     * --json still lists them.
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
     * After the global link for {@code tool} moved: which variables follow it in new shells, and
     * whether this shell picks it up, keeps its own choice, or is overridden by a variable set
     * outside jkit. A shell with the hook whose *_HOME is unset, and no choice of jkit's, gets the
     * global link now, through the hook's file: the hook only sets it when it loads, and on a
     * first install there was nothing to link to then.
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

    /** Whether nothing the user would see changed: jkit's own JKIT_* variables don't count. */
    boolean isEmpty() {
        return changes.stream().allMatch(Changes::bookkeeping);
    }

    /**
     * Whether a change is jkit's own bookkeeping in the shell (JKIT_PROJECT, JKIT_SHELL_JDK,
     * JKIT_USER_JDK...): it changes nothing the user's programs read, so only --json lists it.
     */
    private static boolean bookkeeping(Change c) {
        return c.kind().equals("env") && c.name().startsWith("JKIT_");
    }

    /**
     * Indented lines: one per change (cached files folded into one line, jkit's own JKIT_*
     * variables left out), then the notes.
     */
    void print(PrintStream out) {
        int listsCreated = 0;
        int listsUpdated = 0;
        int listsDeleted = 0;
        Set<String> hosts = new LinkedHashSet<>();
        Path index = ctx.home.index().toAbsolutePath();
        for (Change c : changes) {
            if (bookkeeping(c)) {
                continue;
            }
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
        text = Visible.of(text); // a folder name can hold a newline or an RTL override
        String userHome = System.getProperty("user.home");
        if (text == null || userHome == null || userHome.isEmpty()) {
            return text;
        }
        return Objects.equals(text, userHome) ? "~"
                : text.startsWith(userHome + File.separator) ? "~" + text.substring(userHome.length()) : text;
    }
}
