package dev.jkit.core;

import dev.jkit.model.JkitException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The block {@code jkit hook <shell> --install} adds to a shell's startup file, between marker
 * lines so {@code --uninstall} can take exactly that out again. Only text in and text out; the
 * command does the file work and reports it.
 */
public final class ShellRc {
    public static final String START = "# >>> jkit >>>";
    public static final String END = "# <<< jkit <<<";

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
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    /** The lines between and including the markers. Guarded, so a shell still starts after jkit is gone. */
    public static List<String> block(String shell) {
        String load = shell.equals("fish")
                ? "if type -q jkit; jkit hook fish | source; end"
                : "if command -v jkit >/dev/null 2>&1; then eval \"$(jkit hook " + shell + ")\"; fi";
        return List.of(START, "# added by jkit hook " + shell + " --install; remove with jkit hook " + shell
                + " --uninstall", load, END);
    }

    /** {@code text} with the block at the end, unchanged when it already has the block or loads the hook itself. */
    public static Edit add(String text, String shell) {
        if (hasBlock(text) || manualLine(text) > 0) {
            return new Edit(text, false);
        }
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            sb.append(nl);
        }
        if (!text.isBlank()) {
            sb.append(nl);
        }
        for (String line : block(shell)) {
            sb.append(line).append(nl);
        }
        return new Edit(sb.toString(), true);
    }

    /** {@code text} without the block (and the blank line before it, if it added one). */
    public static Edit remove(String text) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        int start = lines.indexOf(START);
        int end = start < 0 ? -1 : lines.subList(start, lines.size()).indexOf(END);
        if (start < 0 || end < 0) {
            return new Edit(text, false);
        }
        lines.subList(start, start + end + 1).clear();
        if (start > 0 && start == lines.size() && lines.get(start - 1).isBlank()) {
            lines.remove(start - 1);
        }
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        return new Edit(lines.isEmpty() ? "" : String.join(nl, lines) + nl, true);
    }

    public static boolean hasBlock(String text) {
        return text.lines().anyMatch(START::equals);
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
    private static final java.util.regex.Pattern LOADS_HOOK = java.util.regex.Pattern.compile(
            // eval "$(jkit hook bash)", eval "`jkit hook bash`", eval "$(JKIT_AUTO_SWITCH=1 jkit hook bash)"
            "(eval\\s+[\"']?(\\$\\(|`)\\s*(command\\s+|env\\s+|\\w+=\\S*\\s+)*\\S*jkit\\s+hook"
                    + "|\\S*jkit\\s+hook\\b[^|#]*\\|\\s*(source|\\.|iex|Invoke-Expression|Out-String)"
                    + "|(source|\\.)\\s+<\\(\\s*\\S*jkit\\s+hook)");

    public static int manualLine(String text) {
        List<String> lines = text.lines().toList();
        boolean inBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.equals(START)) {
                inBlock = true;
            } else if (line.equals(END)) {
                inBlock = false;
            } else if (!inBlock && !line.startsWith("#") && LOADS_HOOK.matcher(line).find()) {
                return i + 1;
            }
        }
        return 0;
    }
}
