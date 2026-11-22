package dev.jkit.model;

/** Quotes a value as one literal word for bash/zsh, fish or PowerShell. */
public final class ShellQuote {
    private ShellQuote() { }

    public static String quote(String shell, String value) {
        return switch (shell) {
            // fish: inside single quotes only \' and \\ are escapes
            case "fish" -> "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
            // PowerShell: '' inside single quotes; it also treats the typographic quotes as quotes
            case "pwsh", "powershell" -> "'" + value.replaceAll("(['‘’‚‛])", "$1$1") + "'";
            default -> "'" + value.replace("'", "'\\''") + "'";
        };
    }
}
