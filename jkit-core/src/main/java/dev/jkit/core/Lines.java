package dev.jkit.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A text's lines with where each one starts and ends, so an edit can splice in or cut out whole
 * lines and leave every other byte of a user's file as it was: its line endings ({@code \n},
 * {@code \r\n}, or a mix) included.
 */
final class Lines {
    /**
     * One line: {@code text} without its line ending, at {@code [start, end)}; {@code next} is
     * where the following line starts ({@code end} when the text ends without one).
     */
    record Line(String text, int start, int end, int next) {
        /** The line ending as written: "\n", "\r\n", "\r", or "" for a last line without one. */
        String ending(String all) {
            return all.substring(end, next);
        }
    }

    private Lines() { }

    /** The lines, split where {@link String#lines()} splits them. */
    static List<Line> of(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                int next = c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
                lines.add(new Line(text.substring(start, i), start, i, next));
                start = next;
                i = next;
            } else {
                i++;
            }
        }
        if (start < text.length()) {
            lines.add(new Line(text.substring(start), start, text.length(), text.length()));
        }
        return lines;
    }

    /** The line ending for a line jkit adds to the text: "\r\n" if it has any, else "\n". */
    static String ending(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }
}
