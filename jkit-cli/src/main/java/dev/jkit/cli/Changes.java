package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.JkitHome;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ShellRc;
import dev.jkit.model.Envs;
import dev.jkit.model.Platform;
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
import java.util.HashMap;
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
     *   <li>env: set, unset, or add/remove/move for PATH entries;</li>
     *   <li>user-env: the same, for the user's persistent environment variables on Windows;</li>
     *   <li>link: set or remove;</li>
     *   <li>folder: create or delete;</li>
     *   <li>file: create, update or delete. For a cached version list or checksum, {@code value} is
     *       the URL its content came from; otherwise (a project file or shell startup file jkit
     *       wrote, a deleted file) it is null.</li>
     * </ul>
     */
    record Change(Type type, String name, String value) {
        /** Every kind of change with its action, as JSON names them: one enum, so a switch over it is exhaustive. */
        enum Type {
            ENV_SET("env", "set"), ENV_UNSET("env", "unset"),
            ENV_PATH_ADD("env", "add"), ENV_PATH_REMOVE("env", "remove"), ENV_PATH_MOVE("env", "move"),
            USER_ENV_SET("user-env", "set"), USER_ENV_UNSET("user-env", "unset"),
            USER_ENV_PATH_ADD("user-env", "add"), USER_ENV_PATH_REMOVE("user-env", "remove"),
            LINK_SET("link", "set"), LINK_REMOVE("link", "remove"),
            FOLDER_CREATE("folder", "create"), FOLDER_DELETE("folder", "delete"),
            FILE_CREATE("file", "create"), FILE_UPDATE("file", "update"), FILE_DELETE("file", "delete");

            final String kind;
            final String action;

            Type(String kind, String action) {
                this.kind = kind;
                this.action = action;
            }
        }

        String kind() {
            return type.kind;
        }

        String action() {
            return type.action;
        }
    }

    private final Context ctx;
    private final List<Change> changes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** For each PATH entry that moved, what it moved past ("ahead of /usr/bin"), for the text. */
    private final Map<Change, String> moves = new HashMap<>();
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
                changes.add(new Change(Change.Type.ENV_UNSET, name, null));
            } else if (after != null && !after.equals(before)) {
                changes.add(new Change(Change.Type.ENV_SET, name, after));
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

    /**
     * PATH entries added, removed, and moved: an entry that now comes before (or after) one it
     * didn't changes which program runs, as for {@code PATH=/usr/bin:$PATH} and a jkit use whose bin
     * was further back. An empty entry (the current folder) counts like any other.
     */
    private void path(String before, String after) {
        List<String> old = entries(before);
        List<String> now = entries(after);
        for (String entry : now) {
            if (!old.contains(entry)) {
                changes.add(new Change(Change.Type.ENV_PATH_ADD, "PATH", entry));
            }
        }
        for (String entry : old) {
            if (!now.contains(entry)) {
                changes.add(new Change(Change.Type.ENV_PATH_REMOVE, "PATH", entry));
            }
        }
        List<String> oldOrder = old.stream().filter(now::contains).toList();
        List<String> newOrder = now.stream().filter(old::contains).toList();
        Set<String> kept = longestCommon(oldOrder, newOrder);
        for (String entry : newOrder) {
            if (kept.contains(entry)) {
                continue;
            }
            // the first entry it passed, else the first that passed it
            int was = oldOrder.indexOf(entry);
            int is = newOrder.indexOf(entry);
            String detail = null;
            for (String other : newOrder) {
                int otherWas = oldOrder.indexOf(other);
                int otherIs = newOrder.indexOf(other);
                if (otherIs > is && otherWas < was) {
                    detail = "ahead of " + shownEntry(other);
                    break;
                }
            }
            for (int i = newOrder.size() - 1; detail == null && i >= 0; i--) {
                String other = newOrder.get(i);
                if (i < is && oldOrder.indexOf(other) > was) {
                    detail = "behind " + shownEntry(other);
                }
            }
            Change move = new Change(Change.Type.ENV_PATH_MOVE, "PATH", entry);
            changes.add(move);
            moves.put(move, detail);
        }
    }

    /** The entries that keep their order: a longest common subsequence of two orders of the same entries. */
    private static Set<String> longestCommon(List<String> a, List<String> b) {
        int[][] len = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                len[i][j] = a.get(i).equals(b.get(j)) ? len[i + 1][j + 1] + 1 : Math.max(len[i + 1][j], len[i][j + 1]);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0, j = 0; i < a.size() && j < b.size(); ) {
            if (a.get(i).equals(b.get(j))) {
                out.add(a.get(i));
                i++;
                j++;
            } else if (len[i + 1][j] > len[i][j + 1]) { // on a tie the entry now further front is the one that moved
                i++;
            } else {
                j++;
            }
        }
        return out;
    }

    /** PATH entries in order, each once, empty ones (the current folder) included; none for an empty PATH. */
    private static List<String> entries(String path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(Arrays.asList(path.split(File.pathSeparator, -1))));
    }

    private static String shownEntry(String entry) {
        return entry.isEmpty() ? "the empty entry (the current folder)" : tilde(entry);
    }

    /** A global link now points at {@code target}, or was removed (null). */
    Changes link(Path link, Path target) {
        changes.add(target == null
                ? new Change(Change.Type.LINK_REMOVE, link.toAbsolutePath().toString(), null)
                : new Change(Change.Type.LINK_SET, link.toAbsolutePath().toString(), target.toAbsolutePath().toString()));
        return this;
    }

    /** A folder this command created (an install) or deleted (remove, leftovers). */
    Changes folder(Path folder, boolean created) {
        changes.add(new Change(created ? Change.Type.FOLDER_CREATE : Change.Type.FOLDER_DELETE, folder.toAbsolutePath().toString(), null));
        return this;
    }

    /**
     * Adds what the command wrote to disk along the way: the jkit home if it created it, and the
     * version lists and checksums the cache stored. Each is reported once, by whichever Changes
     * asks first.
     */
    Changes disk() {
        if (!ctx.homeExisted && !ctx.homeReported && Files.exists(ctx.home.root())) {
            changes.add(new Change(Change.Type.FOLDER_CREATE, ctx.home.root().toAbsolutePath().toString(), null));
            ctx.homeReported = true;
        }
        for (Context.CacheWrite w : ctx.cacheWrites) {
            changes.add(w.source() == null
                    ? new Change(Change.Type.FILE_DELETE, w.file().toAbsolutePath().toString(), null)
                    : new Change(w.created() ? Change.Type.FILE_CREATE : Change.Type.FILE_UPDATE,
                            w.file().toAbsolutePath().toString(), w.source().toString()));
        }
        ctx.cacheWrites.clear();
        return this;
    }

    /** A variable in the user's persistent environment (Windows, jkit env --install): a USER_ENV_ type. */
    Changes userEnv(Change.Type type, String name, String value) {
        if (!type.kind.equals("user-env")) {
            throw new IllegalArgumentException(type + " is not a user environment change");
        }
        changes.add(new Change(type, name, value));
        return this;
    }

    /** A file this command deleted (jkit hook fish --uninstall). */
    Changes fileDeleted(Path file) {
        changes.add(new Change(Change.Type.FILE_DELETE, file.toAbsolutePath().toString(), null));
        return this;
    }

    /**
     * How to load the hook in new shells. On Windows it is the line to add to the PowerShell
     * profile by hand, since hook --install doesn't edit PowerShell profiles.
     */
    static final String HOOK_SETUP = Platform.hostIsWindows()
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
        changes.add(new Change(created ? Change.Type.FILE_CREATE : Change.Type.FILE_UPDATE, file.toAbsolutePath().toString(), null));
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
            if (named != null && c.type() == Change.Type.FOLDER_CREATE
                    && named.startsWith(Path.of(c.name()).normalize())) {
                continue; // the message names this folder, or one inside it
            }
            if (quietCache && c.kind().equals("file") && Path.of(c.name()).startsWith(index)) {
                continue;
            }
            if (c.type() == Change.Type.FILE_DELETE && Path.of(c.name()).startsWith(index)) {
                listsDeleted++; // old cached lookups, folded into the cache line
                continue;
            }
            if (c.kind().equals("file") && c.value() != null) {
                if (c.type() == Change.Type.FILE_CREATE) {
                    listsCreated++;
                } else {
                    listsUpdated++;
                }
                hosts.add(URI.create(c.value()).getHost());
                continue;
            }
            out.println("  " + switch (c.type()) {
                case ENV_SET -> "set    " + c.name() + "=" + tilde(c.value());
                case ENV_UNSET -> "unset  " + c.name();
                case ENV_PATH_ADD -> "PATH   + " + shownEntry(c.value());
                case ENV_PATH_REMOVE -> "PATH   - " + shownEntry(c.value());
                case ENV_PATH_MOVE -> "PATH   moved " + shownEntry(c.value())
                        + (moves.get(c) == null ? "" : " " + moves.get(c));
                case USER_ENV_SET -> "user   " + c.name() + "=" + tilde(c.value());
                case USER_ENV_UNSET -> "user   " + c.name() + " removed";
                case USER_ENV_PATH_ADD -> "user   PATH + " + tilde(c.value());
                case USER_ENV_PATH_REMOVE -> "user   PATH - " + tilde(c.value());
                case LINK_SET -> "link   " + tilde(c.name()) + " → " + tilde(c.value());
                case LINK_REMOVE -> "link   " + tilde(c.name()) + " removed";
                case FOLDER_CREATE -> "folder " + tilde(c.name()) + " created";
                case FOLDER_DELETE -> "folder " + tilde(c.name()) + " deleted";
                case FILE_CREATE -> "file   " + tilde(c.name()) + " created";
                case FILE_UPDATE -> "file   " + tilde(c.name()) + " updated";
                case FILE_DELETE -> "file   " + tilde(c.name()) + " deleted";
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
