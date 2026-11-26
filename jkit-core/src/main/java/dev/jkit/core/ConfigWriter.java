package dev.jkit.core;

import dev.jkit.model.JkitException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Sets one value in {@code config.toml}, such as {@code auto_switch = true} under {@code [shell]},
 * for {@code jkit hook --install --auto-switch}. Like {@link ProjectWriter} it changes one line and
 * leaves comments and order alone, then reads the result back: if anything but that value changed,
 * or the value didn't, it refuses instead of writing a broken file.
 */
public final class ConfigWriter {
    private static final Pattern HEADER = Pattern.compile("^\\s*\\[\\s*([^\\[\\]]+?)\\s*\\]\\s*(#.*)?$");
    private static final Pattern ARRAY_HEADER = Pattern.compile("^\\s*\\[\\[.*");
    private static final Pattern KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_-]+|\"[^\"]*\"|'[^']*')\\s*=");

    /** The new text, and whether it differs from the old. */
    public record Edit(String text, boolean changed) { }

    private ConfigWriter() { }

    /** {@code text} with {@code key = value} in {@code [table]}; {@code value} is a TOML literal such as {@code true}. */
    public static Edit set(Path file, String text, String table, String key, String value) {
        TomlParseResult before = parse(file, text);
        String dotted = table + "." + key;
        if (Objects.equals(before.get(dotted), Toml.parse(dotted + " = " + value).get(dotted))) {
            return new Edit(text, false); // already so: leave the file exactly as it is
        }
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(text.lines().toList());
        Scan scan = scan(lines, table, key);
        int section = scan.section;
        int lastKey = scan.lastKey;
        int line = scan.line;
        int valueStart = scan.valueStart;
        if (line >= 0) {
            String old = lines.get(line);
            Matcher k = KEY.matcher(old);
            k.find();
            lines.set(line, k.group(1) + k.group(2) + " = " + value + ProjectWriter.trailingComment(old, valueStart));
        } else if (section >= 0) {
            lines.add(lastKey + 1, key + " = " + value);
        } else {
            while (!lines.isEmpty() && lines.getLast().isBlank()) {
                lines.removeLast();
            }
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("[" + table + "]");
            lines.add(key + " = " + value);
        }
        String result = String.join(nl, lines) + nl;
        verify(file, before, result, dotted, Toml.parse(dotted + " = " + value).get(dotted));
        return new Edit(result, true);
    }

    /**
     * {@code text} without {@code key} in {@code [table]}; a table left with nothing in it (no keys,
     * no comments) goes too. Unchanged when the key isn't set there.
     */
    public static Edit unset(Path file, String text, String table, String key) {
        TomlParseResult before = parse(file, text);
        String dotted = table + "." + key;
        if (!before.contains(dotted)) {
            return new Edit(text, false);
        }
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(text.lines().toList());
        Scan scan = scan(lines, table, key);
        if (scan.line < 0) {
            throw cantEdit(file, dotted); // set some other way, e.g. a dotted key at the top
        }
        lines.remove(scan.line);
        int end = scan.section + 1;
        while (end < lines.size() && !HEADER.matcher(lines.get(end)).matches()
                && !ARRAY_HEADER.matcher(lines.get(end)).matches()) {
            if (!lines.get(end).isBlank()) {
                end = -1; // something else still in the table
                break;
            }
            end++;
        }
        if (end >= 0) {
            lines.subList(scan.section, end).clear(); // the header and the blank lines after it
            while (!lines.isEmpty() && lines.getLast().isBlank()) {
                lines.removeLast();
            }
        }
        String result = lines.isEmpty() ? "" : String.join(nl, lines) + nl;
        verify(file, before, result, dotted, null);
        return new Edit(result, true);
    }

    /** Where {@code key} sits in the first {@code [table]}: header, last key line, the key's line and value offset. */
    private record Scan(int section, int lastKey, int line, int valueStart) { }

    private static Scan scan(List<String> lines, String table, String key) {
        int section = -1;
        int lastKey = -1;
        int line = -1;
        int valueStart = 0;
        boolean inTable = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher header = HEADER.matcher(lines.get(i));
            if (ARRAY_HEADER.matcher(lines.get(i)).matches() || header.matches()) {
                inTable = header.matches() && ProjectWriter.unquote(header.group(1)).equals(table);
                if (inTable && section < 0) {
                    section = i;
                    lastKey = i;
                }
                continue;
            }
            Matcher k = KEY.matcher(lines.get(i));
            if (inTable && k.find()) {
                lastKey = i;
                if (ProjectWriter.unquote(k.group(2)).equals(key) && line < 0) {
                    line = i;
                    valueStart = k.end();
                }
            }
        }
        return new Scan(section, lastKey, line, valueStart);
    }

    /**
     * Reads {@code result} back: {@code dotted} must now be {@code want} (null: gone), and every
     * other value exactly as it was; otherwise refuses rather than write a file that changed more.
     */
    private static void verify(Path file, TomlParseResult before, String result, String dotted, Object want) {
        TomlParseResult after;
        try {
            after = parse(file, result);
        } catch (JkitException e) {
            throw cantEdit(file, dotted);
        }
        if (!Objects.equals(after.get(dotted), want)) {
            throw cantEdit(file, dotted);
        }
        for (String other : before.dottedKeySet()) {
            if (!other.equals(dotted) && !Objects.equals(before.get(other), after.get(other))) {
                throw cantEdit(file, dotted);
            }
        }
        int expected = before.dottedKeySet().size() + (want == null ? -1 : before.contains(dotted) ? 0 : 1);
        if (after.dottedKeySet().size() != expected) {
            throw cantEdit(file, dotted);
        }
    }

    private static TomlParseResult parse(Path file, String text) {
        TomlParseResult toml = TextFiles.parseToml(file, text);
        if (toml.hasErrors()) {
            throw new JkitException("invalid " + file + ": " + toml.errors().getFirst(), "fix it by hand first");
        }
        return toml;
    }

    private static JkitException cantEdit(Path file, String dotted) {
        int dot = dotted.indexOf('.');
        return new JkitException("can't set " + dotted + " in " + file + " safely",
                "edit it by hand: " + dotted.substring(dot + 1) + " = … under [" + dotted.substring(0, dot) + "]");
    }
}
