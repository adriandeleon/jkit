package dev.jkit.model;

/**
 * Text from outside (folder names, TOML keys) made safe to print on one line: control characters,
 * line and paragraph separators and bidirectional overrides become {@code \xNN} or {@code &#92;u{NNNN}},
 * so a folder named {@code "x\nok: all good"} can't forge a line of output and an RTL override
 * can't reorder what the line seems to say.
 */
public final class Visible {
    private Visible() { }

    public static String of(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String escaped = escape(c);
            if (escaped != null && out == null) {
                out = new StringBuilder(text.length() + 8).append(text, 0, i);
            }
            if (out != null) {
                if (escaped != null) {
                    out.append(escaped);
                } else {
                    out.append(c);
                }
            }
        }
        return out == null ? text : out.toString();
    }

    private static String escape(char c) {
        if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
            return String.format("\\x%02x", (int) c);
        }
        boolean bidi = c == 0x061c || c == 0x200e || c == 0x200f || (c >= 0x202a && c <= 0x202e)
                || (c >= 0x2066 && c <= 0x2069);
        // zero-width and invisible characters could hide text in a name
        boolean invisible = (c >= 0x200b && c <= 0x200d) || (c >= 0x2060 && c <= 0x2064) || c == 0xfeff
                || c == 0x00ad;
        if (bidi || invisible || c == 0x2028 || c == 0x2029) {
            return String.format("\\u{%04x}", (int) c);
        }
        return null;
    }
}
