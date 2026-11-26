package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits the {@code [tools]} table of a {@code jkit.toml} for {@code jkit pin} and {@code jkit unpin}.
 * tomlj can only read, so this changes one line and leaves comments, order and spacing alone.
 * Every edit is read back with {@link ProjectFile}: if the result doesn't pin exactly what was
 * asked, or changes another tool, the edit is refused instead of writing a broken file.
 */
public final class ProjectWriter {
    private static final Pattern HEADER = Pattern.compile("^\\s*\\[\\s*([^\\[\\]]+?)\\s*\\]\\s*(#.*)?$");
    private static final Pattern ARRAY_HEADER = Pattern.compile("^\\s*\\[\\[.*");
    private static final Pattern KEY = Pattern.compile("^(\\s*)(\"[^\"]*\"|'[^']*'|[A-Za-z0-9_-]+)\\s*=");

    /** The new text and whether the tool had a line before. */
    public record Edit(String text, boolean replaced) { }

    private ProjectWriter() { }

    /** A new file holding just {@code pin}. */
    public static String fresh(PinnedTool pin) {
        return "# Versions jkit uses in this project: jkit sync installs them.\n"
                + "[tools]\n" + line(pin.tool().id(), pin) + "\n";
    }

    /**
     * {@code text} with {@code pin} set: its line replaced in place, or added to {@code [tools]}.
     * Every other line stays character for character as it was, line endings included; written
     * back with {@link ProjectFile#write}, the file's charset and byte-order mark stay too, so
     * every other byte does.
     */
    public static Edit set(Path file, String text, PinnedTool pin) {
        List<Lines.Line> lines = Lines.of(text);
        Located found = locate(lines.stream().map(Lines.Line::text).toList(), pin.tool());
        String result;
        boolean replaced;
        if (found.line >= 0) {
            Lines.Line old = lines.get(found.line);
            result = text.substring(0, old.start())
                    + found.indent + found.key + " = " + value(pin) + trailingComment(old.text(), found.valueStart)
                    + text.substring(old.end());
            replaced = true;
        } else if (found.section >= 0) {
            Lines.Line after = lines.get(found.lastKey);
            String nl = after.next() > after.end() ? after.ending(text) : Lines.ending(text);
            String added = line(pin.tool().id(), pin);
            // a last line without a line ending gets one, and the new line goes after it
            result = after.next() > after.end()
                    ? text.substring(0, after.next()) + added + nl + text.substring(after.next())
                    : text + nl + added + nl;
            replaced = false;
        } else {
            String nl = Lines.ending(text);
            int keep = lines.size();
            while (keep > 0 && lines.get(keep - 1).text().isBlank()) {
                keep--;
            }
            StringBuilder sb = new StringBuilder();
            if (keep > 0) {
                Lines.Line last = lines.get(keep - 1);
                sb.append(text, 0, last.end()).append(last.next() > last.end() ? last.ending(text) : nl).append(nl);
            }
            result = sb.append("[tools]").append(nl).append(line(pin.tool().id(), pin)).append(nl).toString();
            replaced = false;
        }
        check(file, text, result, pin.tool(), pin);
        return new Edit(result, replaced);
    }

    /**
     * {@code text} without the tool's line; {@code replaced} false (and the text unchanged) when it
     * had none. Every other character stays as it was, and every other byte when written back
     * with {@link ProjectFile#write}.
     */
    public static Edit remove(Path file, String text, Tool tool) {
        List<Lines.Line> lines = Lines.of(text);
        Located found = locate(lines.stream().map(Lines.Line::text).toList(), tool);
        if (found.line < 0) {
            check(file, text, text, tool, null);
            return new Edit(text, false);
        }
        Lines.Line gone = lines.get(found.line);
        String result = text.substring(0, gone.start()) + text.substring(gone.next());
        check(file, text, result, tool, null);
        return new Edit(result, true);
    }

    /** {@code "21"}, or {@code { version = "21", vendor = "zulu", fx = true }} when vendor or fx matter. */
    public static String value(PinnedTool pin) {
        String version = pin.version() == null ? "latest" : pin.version();
        if (pin.vendor() == null && !pin.fx()) {
            return quote(version);
        }
        StringBuilder sb = new StringBuilder("{ version = ").append(quote(version));
        if (pin.vendor() != null) {
            sb.append(", vendor = ").append(quote(pin.vendor().name()));
        }
        if (pin.fx()) {
            sb.append(", fx = true");
        }
        return sb.append(" }").toString();
    }

    private static String line(String key, PinnedTool pin) {
        return key + " = " + value(pin);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Where the tool's line is (or -1), and where {@code [tools]} is and its last key line. */
    private static final class Located {
        int section = -1;
        int lastKey = -1;
        int line = -1;
        int valueStart;
        String indent = "";
        String key;
    }

    private static Located locate(List<String> lines, Tool tool) {
        Located at = new Located();
        boolean inTools = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher header = HEADER.matcher(line);
            if (ARRAY_HEADER.matcher(line).matches() || header.matches()) {
                inTools = header.matches() && unquote(header.group(1)).equals("tools");
                if (inTools && at.section < 0) {
                    at.section = i;
                    at.lastKey = i;
                }
                continue;
            }
            if (!inTools) {
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (!key.find()) {
                continue;
            }
            at.lastKey = i;
            if (Tool.find(unquote(key.group(2))).orElse(null) == tool && at.line < 0) {
                at.line = i;
                at.indent = key.group(1);
                at.key = key.group(2);
                at.valueStart = key.end();
            }
        }
        return at;
    }

    /** A TOML key without its quotes. */
    static String unquote(String key) {
        String k = key.trim();
        if (k.length() >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'"))) {
            return k.substring(1, k.length() - 1);
        }
        return k;
    }

    /** The {@code  # comment} after a value, kept when the value is replaced; "" when there is none. */
    static String trailingComment(String line, int from) {
        char quote = 0;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                int start = i;
                while (start > from && Character.isWhitespace(line.charAt(start - 1))) {
                    start--;
                }
                return line.substring(start);
            }
        }
        return "";
    }

    /**
     * Reads the edited text back: it must pin {@code want} for {@code tool} (or nothing, when null)
     * and leave every other tool as it was. Catches layouts the line edit doesn't understand, such
     * as {@code [tools.jdk]} tables or multi-line strings.
     */
    private static void check(Path file, String before, String after, Tool tool, PinnedTool want) {
        ProjectSpec old = ProjectFile.readToml(file, before);
        ProjectSpec now;
        try {
            now = ProjectFile.readToml(file, after);
        } catch (JkitException e) {
            throw cantEdit(file, tool);
        }
        Map<Tool, PinnedTool> others = new EnumMap<>(Tool.class);
        others.putAll(old.tools());
        others.remove(tool);
        Map<Tool, PinnedTool> nowOthers = new EnumMap<>(Tool.class);
        nowOthers.putAll(now.tools());
        nowOthers.remove(tool);
        if (!Objects.equals(now.get(tool), want) || !others.equals(nowOthers)) {
            throw cantEdit(file, tool);
        }
    }

    private static JkitException cantEdit(Path file, Tool tool) {
        return new JkitException("can't edit " + tool + " in " + file + " safely: jkit only changes plain "
                + "'" + tool.id() + " = …' lines under a [tools] header, and this file sets its tools another way "
                + "(an inline table, dotted keys, or a value over several lines)", "edit the file by hand");
    }
}
