package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.ShellQuote;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The block {@code jkit hook <shell> --install} adds to a shell's startup file, between marker
 * lines so {@code --uninstall} can take exactly that out again. Only text in and text out; the
 * command does the file work and reports it.
 */
public final class ShellRc {
    public static final String START = "# >>> jkit >>>";
    public static final String END = "# <<< jkit <<<";
    /** The block line that says --install made the file, so --uninstall may delete it once blank. */
    public static final String CREATED = "# jkit created this file: --uninstall deletes it if nothing else is in it";

    /** The new text, and whether it differs from the old. */
    public record Edit(String text, boolean changed) { }

    private ShellRc() { }

    /**
     * Where the hook goes: {@code ~/.bashrc}, {@code $ZDOTDIR/.zshrc} (else {@code ~/.zshrc}), or a
     * file of its own for fish, {@code $XDG_CONFIG_HOME/fish/conf.d/jkit.fish}.
     */
    public static Path file(String shell, Map<String, String> env, Path userHome) {
        return switch (shell) {
            case "bash" -> userHome.resolve(".bashrc");
            case "zsh" -> dirOr(env.get("ZDOTDIR"), userHome).resolve(".zshrc");
            case "fish" -> dirOr(env.get("XDG_CONFIG_HOME"), userHome.resolve(".config")).resolve("fish/conf.d/jkit.fish");
            default -> throw new JkitException("jkit can't add the hook to " + shell + "'s startup file",
                    "PowerShell: add to your $PROFILE:  jkit hook pwsh | Out-String | Invoke-Expression");
        };
    }

    private static Path dirOr(String value, Path fallback) {
        // relative values (rare, and not what the shell means either) fall back like unset ones
        Path path = Fs.path(value);
        return path == null || !path.isAbsolute() ? fallback : path;
    }

    /** The lines between and including the markers. Guarded, so a shell still starts after jkit is gone. */
    public static List<String> block(String shell) {
        return block(shell, null, null);
    }

    /**
     * The block for the jkit at {@code self}: a shell started from a shell (tmux, an IDE terminal,
     * {@code bash}) inherits a PATH with a project's tool bin folders first, so a {@code bin/jkit}
     * in a JDK must not be what prints the hook. If {@code self} is gone (moved, upgraded), the first
     * {@code jkit} on PATH outside {@code root}, jkit's own folder, loads it instead. With either
     * null, a plain PATH lookup.
     */
    public static List<String> block(String shell, String self, Path root) {
        List<String> lines = new ArrayList<>();
        lines.add(START);
        lines.add("# added by jkit hook " + shell + " --install; remove with jkit hook " + shell + " --uninstall");
        if (self == null || root == null) {
            // conf.d is read by every fish, scripts and fish -c too; ~/.bashrc and ~/.zshrc only by interactive shells
            lines.add(shell.equals("fish")
                    ? "if status is-interactive; and type -q jkit; jkit hook fish | source; end"
                    : "if command -v jkit >/dev/null 2>&1; then eval \"$(jkit hook " + shell + ")\"; fi");
        } else {
            String bin = ShellQuote.quote(shell, self);
            String under = ShellQuote.quote(shell, root.toAbsolutePath().normalize() + "/");
            switch (shell) {
                case "fish" -> {
                    // conf.d is read by every fish, scripts and fish -c too: only interactive ones get the hook
                    lines.add("if status is-interactive");
                    lines.add("  set -l __jkit " + bin);
                    lines.add("  if not test -x $__jkit; set __jkit; for d in $PATH; if not string match -q -- "
                            + ShellQuote.quote("fish", root.toAbsolutePath().normalize() + "/*") + " \"$d/\"; "
                            + "and test -x $d/jkit; set __jkit $d/jkit; break; end; end; end");
                    lines.add("  if test -n \"$__jkit\"; $__jkit hook fish | source; end");
                    lines.add("end");
                }
                default -> {
                    lines.add("__jkit=" + bin);
                    if (shell.equals("zsh")) {
                        lines.add("if [ ! -x \"$__jkit\" ]; then __jkit=; for __jkit_d in ${(s.:.)PATH}; do case \"$__jkit_d/\" in "
                                + under + "*) ;; *) if [ -x \"$__jkit_d/jkit\" ]; then __jkit=$__jkit_d/jkit; break; fi ;; "
                                + "esac; done; unset __jkit_d; fi");
                    } else {
                        lines.add("if [ ! -x \"$__jkit\" ]; then __jkit=; IFS=: read -r -a __jkit_p <<< \"$PATH\"; "
                                + "for __jkit_d in \"${__jkit_p[@]}\"; do case \"$__jkit_d/\" in "
                                + under + "*) ;; *) if [ -x \"$__jkit_d/jkit\" ]; then __jkit=$__jkit_d/jkit; break; fi ;; "
                                + "esac; done; unset __jkit_p __jkit_d; fi");
                    }
                    lines.add("if [ -n \"$__jkit\" ]; then eval \"$(\"$__jkit\" hook " + shell + ")\"; fi; unset __jkit");
                }
            }
        }
        lines.add(END);
        return lines;
    }

    /** {@code text} with the block at the end, unchanged when it already has the block or loads the hook itself. */
    public static Edit add(String text, String shell) {
        return add(text, shell, null, null);
    }

    /** {@link #add(String, String)} with the block for the jkit at {@code self}. */
    public static Edit add(String text, String shell, String self, Path root) {
        return add(text, shell, self, root, false);
    }

    /** {@link #add(String, String, String, Path)}; {@code created} when the file didn't exist before. */
    public static Edit add(String text, String shell, String self, Path root, boolean created) {
        if (hasBlock(text) || unterminatedStart(text) > 0 || manualLine(text) > 0) {
            return new Edit(text, false);
        }
        // the block is always \n: bash and zsh read a \r\n block's last line as "fi\r" and fail to
        // start it. A last line of the user's that has no line ending gets the file's own.
        StringBuilder sb = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r")) {
            sb.append(Lines.ending(text));
        }
        if (!text.isBlank()) {
            sb.append('\n');
        }
        List<String> lines = new ArrayList<>(block(shell, self, root));
        if (created) {
            lines.add(2, CREATED);
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return new Edit(sb.toString(), true);
    }

    /**
     * {@code text} without the block (and the blank line before it, if it added one). Every other
     * byte stays as it was, the file's line endings included.
     */
    public static Edit remove(String text) {
        List<Lines.Line> lines = Lines.of(text);
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.size() && end < 0; i++) {
            String line = lines.get(i).text();
            if (start < 0 && is(line, START)) {
                start = i;
            } else if (start >= 0 && is(line, END)) {
                end = i;
            }
        }
        if (start < 0 || end < 0) {
            return new Edit(text, false);
        }
        int from = lines.get(start).start();
        int to = lines.get(end).next();
        if (to == text.length() && start > 0 && lines.get(start - 1).text().isBlank()) {
            from = lines.get(start - 1).start();
        }
        return new Edit(text.substring(0, from) + text.substring(to), true);
    }

    /** Whether jkit's block says --install created the file. */
    public static boolean createdByJkit(String text) {
        boolean inBlock = false;
        for (String line : text.lines().toList()) {
            if (is(line, START)) {
                inBlock = true;
            } else if (is(line, END)) {
                inBlock = false;
            } else if (inBlock && is(line, CREATED)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the text has jkit's block, from its start marker to its end marker. */
    public static boolean hasBlock(String text) {
        boolean started = false;
        for (String line : text.lines().toList()) {
            if (is(line, START)) {
                started = true;
            } else if (started && is(line, END)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The 1-based number of a start marker with no end marker after it (one deleted by hand), or 0.
     * jkit then can't tell where its block ends, so it changes nothing.
     */
    public static int unterminatedStart(String text) {
        List<String> lines = text.lines().toList();
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (start == 0 && is(lines.get(i), START)) {
                start = i + 1;
            } else if (start > 0 && is(lines.get(i), END)) {
                start = 0;
            }
        }
        return start;
    }

    /** A marker line, also indented or with spaces after it (an editor's, a formatter's). */
    private static boolean is(String line, String marker) {
        return line.strip().equals(marker);
    }

    /**
     * The 1-based number of a line outside jkit's block that loads the hook ({@code jkit hook}, not
     * commented out), or 0: someone added it by hand, so jkit leaves it alone.
     */
    /**
     * Lines that load the hook: {@code eval "$(jkit hook bash)"}, {@code jkit hook fish | source},
     * {@code jkit hook pwsh | Invoke-Expression}, {@code source <(jkit hook zsh)}. Not an alias or a
     * function that merely mentions it ({@code alias jh='jkit hook bash'}).
     */
    private static final Pattern LOADS_HOOK = Pattern.compile(
            // eval "$(jkit hook bash)", eval "`jkit hook bash`", eval "$(JKIT_AUTO_SWITCH=1 jkit hook bash)"
            "(eval\\s+[\"']?(\\$\\(|`)\\s*(command\\s+|env\\s+|\\w+=\\S*\\s+)*\\S*jkit\\s+hook"
                    + "|\\S*jkit\\s+hook\\b[^|#]*\\|\\s*(source|\\.|iex|Invoke-Expression|Out-String)"
                    + "|(source|\\.)\\s+<\\(\\s*\\S*jkit\\s+hook)");

    public static int manualLine(String text) {
        List<String> lines = text.lines().toList();
        boolean inBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (is(line, START)) {
                inBlock = true;
            } else if (is(line, END)) {
                inBlock = false;
            } else if (!inBlock && !line.startsWith("#") && LOADS_HOOK.matcher(line).find()) {
                return i + 1;
            }
        }
        return 0;
    }
}
