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
        int section = -1;
        int lastKey = -1;
        int line = -1;
        int valueStart = 0;
        boolean inTable = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher header = HEADER.matcher(lines.get(i));
            if (ARRAY_HEADER.matcher(lines.get(i)).matches() || header.matches()) {
                inTable = header.matches() && unquote(header.group(1)).equals(table);
                if (inTable && section < 0) {
                    section = i;
                    lastKey = i;
                }
                continue;
            }
            Matcher k = KEY.matcher(lines.get(i));
            if (inTable && k.find()) {
                lastKey = i;
                if (unquote(k.group(2)).equals(key) && line < 0) {
                    line = i;
                    valueStart = k.end();
                }
            }
        }
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
        TomlParseResult after;
        try {
            after = parse(file, result);
        } catch (JkitException e) {
            throw cantEdit(file, dotted);
        }
        Object want = Toml.parse(dotted + " = " + value).get(dotted);
        if (!Objects.equals(after.get(dotted), want)) {
            throw cantEdit(file, dotted);
        }
        for (String other : before.dottedKeySet()) {
            if (!other.equals(dotted) && !Objects.equals(before.get(other), after.get(other))) {
                throw cantEdit(file, dotted);
            }
        }
        if (after.dottedKeySet().size() != before.dottedKeySet().size() + (before.contains(dotted) ? 0 : 1)) {
            throw cantEdit(file, dotted);
        }
        return new Edit(result, true);
    }

    private static TomlParseResult parse(Path file, String text) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new JkitException("invalid " + file + ": " + toml.errors().getFirst(), "fix it by hand first");
        }
        return toml;
    }

    private static String unquote(String key) {
        String k = key.trim();
        if (k.length() >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'"))) {
            return k.substring(1, k.length() - 1);
        }
        return k;
    }

    private static JkitException cantEdit(Path file, String dotted) {
        int dot = dotted.indexOf('.');
        return new JkitException("can't set " + dotted + " in " + file + " safely",
                "edit it by hand: " + dotted.substring(dot + 1) + " = … under [" + dotted.substring(0, dot) + "]");
    }
}
