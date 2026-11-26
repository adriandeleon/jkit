package dev.jkit.model;

/** Quotes a value as one literal word for bash/zsh, fish or PowerShell. */
public final class ShellQuote {
    private ShellQuote() { }

    public static String quote(String shell, String value) {
        return switch (shell) {
            // fish: inside single quotes only \' and \\ are escapes
            case "fish" -> "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
            // PowerShell: '' inside single quotes; it also treats the typographic quotes as quotes
            case "pwsh", "powershell" -> "'" + value.replaceAll("(['\u2018\u2019\u201a\u201b])", "$1$1") + "'";
            default -> "'" + value.replace("'", "'\\''") + "'";
        };
    }

    /**
     * {@link #quote} for shell code that jkit prints for a shell to run (the hook, jkit env), rather
     * than for a person to read. For PowerShell it is ASCII only: PowerShell reads a program's
     * output in the console's OEM code page, while jkit writes it in the ANSI one, so a
     * {@code C:\Users\Jos\u00e9} would arrive as another path. Each character past ASCII is written
     * as {@code [char]0x00E9}, joined to the quoted text around it:
     * {@code ('C:\Users\Jos' + [char]0x00E9)}; a character past U+FFFF is its two UTF-16 halves.
     */
    public static String script(String shell, String value) {
        if (!shell.equals("pwsh") && !shell.equals("powershell") || value.chars().allMatch(c -> c < 0x80)) {
            return quote(shell, value);
        }
        StringBuilder out = new StringBuilder("(");
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                text.append(c == '\'' ? "''" : String.valueOf(c));
                continue;
            }
            // the first part is always a string, so + joins strings rather than adding numbers
            if (first || !text.isEmpty()) {
                out.append(first ? "" : " + ").append('\'').append(text).append('\'');
                text.setLength(0);
            }
            out.append(" + [char]0x").append(String.format("%04X", (int) c));
            first = false;
        }
        if (!text.isEmpty()) {
            out.append(" + '").append(text).append('\'');
        }
        return out.append(')').toString();
    }
}
