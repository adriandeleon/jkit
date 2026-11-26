package dev.jkit.platform;

import dev.jkit.model.ShellQuote;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell hook scripts. Each adds the global bin folders to PATH (for a *_HOME the user set, that
 * one's bin instead, so java and JAVA_HOME agree), sets the global *_HOME variables when unset,
 * and defines a {@code jkit} function that sources the environment changes the binary writes to
 * $JKIT_ENV_FILE.
 *
 * <p>The scripts live in {@code hooks/} next to this class. A template names each value it needs
 * as {@code @NAME@}; every value is quoted for the target shell before it goes in, so a path with
 * quotes, spaces or {@code $} in it stays one literal word.
 */
final class Hooks {
    private static final Pattern TOKEN = Pattern.compile("@([A-Z_]+)@");

    private Hooks() { }

    static String posix(String shell, Path root) {
        return posix(shell, root, null);
    }

    static String posix(String shell, Path root, String self) {
        if (!shell.equals("bash") && !shell.equals("zsh")) {
            throw new IllegalArgumentException("not a POSIX hook shell: " + shell);
        }
        // the shell's name goes in unquoted (it's one of the two above); the rest are paths
        return render("hook.sh", Map.of(
                "SHELL", shell,
                "GLOBAL", ShellQuote.quote(shell, root.resolve("global").toString()),
                "SELF", quoteSelf(shell, self),
                "ROOT", quoteRoot(shell, root)));
    }

    /**
     * The part of the hook that switches to a project's versions on {@code cd}
     * ({@code shell.auto_switch}): runs {@code jkit hook-env} when the directory changes and once
     * when the hook loads. Null for shells without it (PowerShell, for now). It calls jkit through
     * the main hook's {@code __jkit_run}, so it needs no path of its own.
     */
    static String autoSwitch(String shell) {
        return switch (shell) {
            case "bash", "zsh", "fish" -> render("auto-switch." + shell, Map.of());
            default -> null;
        };
    }

    static String fish(Path root) {
        return fish(root, null);
    }

    static String fish(Path root, String self) {
        return render("hook.fish", Map.of(
                "GLOBAL", ShellQuote.quote("fish", root.resolve("global").toString()),
                "SELF", quoteSelf("fish", self),
                "ROOT", quoteRoot("fish", root)));
    }

    static String powershell(Path root) {
        return powershell(root, null);
    }

    static String powershell(Path root, String self) {
        return render("hook.ps1", Map.of(
                "GLOBAL", ShellQuote.quote("pwsh", root.resolve("global").toString()),
                "SELF", quoteSelf("pwsh", self),
                "ROOT", quoteRoot("pwsh", root)));
    }

    /**
     * The hook's {@code __jkit_run} runs the binary at this absolute path, so a {@code bin/jkit} in
     * a JDK or build tool that a project puts first on PATH is never what runs on {@code cd}. When
     * the path is unknown (empty) or later gone, because a package manager moved the binary, the
     * hook looks jkit up on PATH instead.
     */
    private static String quoteSelf(String shell, String self) {
        return ShellQuote.quote(shell, self == null ? "" : self);
    }

    private static String quoteRoot(String shell, Path root) {
        return ShellQuote.quote(shell, root.toAbsolutePath().normalize().toString());
    }

    /**
     * Replaces each {@code @NAME@} in the template with its value, in one pass: a value that itself
     * contains {@code @X@} is never expanded again. A token without a value is a bug in the
     * template, so it fails rather than reaching a user's shell.
     */
    private static String render(String template, Map<String, String> values) {
        Matcher m = TOKEN.matcher(load(template));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalStateException("hooks/" + template + ": no value for @" + m.group(1) + "@");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String load(String template) {
        try (InputStream in = Hooks.class.getResourceAsStream("hooks/" + template)) {
            if (in == null) {
                throw new IllegalStateException("hooks/" + template + " is missing from the build");
            }
            // a checkout with CRLF line endings must not change what the shells get
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
