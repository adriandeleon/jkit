package dev.jkit.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.Store;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Visible;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Option;

/**
 * {@code --json}: stdout holds exactly one JSON document, also on failure, so tools such as
 * editors can drive jkit. Progress and warnings stay on stderr. Jackson tree model only (no data
 * binding), so native-image needs no reflection setup. The shape is documented in docs/json.md;
 * bump {@link #SCHEMA_VERSION} on any change that is not purely additive.
 */
final class JsonOut {
    static final int SCHEMA_VERSION = 1;
    static final String FLAG = "--json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.getFactory().setCharacterEscapes(new TerminalSafeEscapes());
    }

    /**
     * Jackson escapes C0 controls already; this adds DEL and the C1 controls ({@code \u007f},
     * {@code \u0080..\u009f}), so a document printed to a terminal can't carry an escape sequence, and
     * the characters {@link Visible#hidden} names, so an agent reading it sees them as escapes.
     * stdout under {@code --json} skips {@link SafeOutput}, whose {@code \xNN} isn't valid JSON.
     */
    private static final class TerminalSafeEscapes extends CharacterEscapes {
        private final int[] table = standardAsciiEscapesForJSON();

        TerminalSafeEscapes() {
            table[0x7f] = ESCAPE_STANDARD;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return table;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            // Jackson calls this per UTF-16 unit, so a Unicode tag character (U+E0041 is \udb40\udc41)
            // arrives as two surrogates: escape every surrogate, which is still valid JSON and the only
            // stateless way to escape both halves of a tag character without splitting other pairs
            boolean surrogate = Character.isSurrogate((char) ch);
            return ch >= 0x80 && ch <= 0x9f || surrogate || Visible.hidden(ch)
                    ? new SerializedString(String.format("\\u%04x", ch))
                    : null;
        }
    }

    /** Pretty JSON text with the escapes above. */
    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mixin for commands that support {@code --json}. */
    static final class Flag {
        @Option(names = FLAG, description = "Print one JSON document on stdout instead of text (see docs/json.md).")
        boolean json;
    }

    private JsonOut() { }

    static void print(PrintStream out, Map<String, Object> doc) {
        out.println(write(doc));
        out.flush();
    }

    /**
     * {@code {"schemaVersion": 1, "error": {"message", "hints", "exitCode"}}}, plus {@code changes}
     * when the command had already changed something before it failed.
     */
    static Map<String, Object> error(JkitException e, int exitCode) {
        Map<String, Object> doc = document();
        if (e instanceof PartialFailure partial && !partial.changes().isEmpty()) {
            doc.put("changes", partial.changes());
        }
        doc.put("error", fields("message", Errors.capped(e.getMessage()),
                "hints", e.hints().stream().map(Errors::capped).toList(),
                "exitCode", exitCode));
        return doc;
    }

    /** One installed build, with where it is active. */
    static Map<String, Object> installed(Context ctx, Installed i) throws Exception {
        List<String> global = new ArrayList<>();
        List<String> shell = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (t.storeTool() != i.tool()) {
                continue;
            }
            Path link = ctx.os.readLink(ctx.home.global(t));
            if (Fs.same(link, i.home())) {
                global.add(t.id());
            }
            String state = ctx.env.get(ShellEnv.stateVariable(t));
            if (state != null && Fs.same(Fs.path(state), i.home())) {
                shell.add(t.id());
            }
        }
        return fields(
                "tool", i.tool().id(),
                "id", i.id(),
                "version", i.version().raw(),
                "major", i.major(),
                "vendor", i.vendor() == null ? null : i.vendor().name(),
                "fx", i.fx(),
                "graalvm", i.vendor() != null && i.vendor().isGraalVmBased(),
                "preRelease", i.version().isPreRelease(),
                "home", i.home().toAbsolutePath().toString(),
                // checked against a sha256/384/512 checksum when it was installed (see docs/json.md)
                "verified", Store.verified(i.home()),
                "signedBy", Store.signedBy(i.home()),
                "global", global,
                "activeInShell", shell);
    }

    /** A top-level document as a map, {@code schemaVersion} first; {@link #write} turns it into JSON. */
    static Map<String, Object> document() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        return doc;
    }

    /** A JSON object as a map with fields in the order given: key, value, key, value, … */
    static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** Maps, collections, Jackson nodes, strings, numbers, booleans, paths (absolute) and null, pretty-printed. */
    static String write(Object value) {
        return pretty(toNode(value));
    }

    private static JsonNode toNode(Object value) {
        var f = MAPPER.getNodeFactory();
        return switch (value) {
            case null -> f.nullNode();
            case JsonNode n -> n;
            case Map<?, ?> map -> {
                ObjectNode node = f.objectNode();
                map.forEach((k, v) -> node.set(String.valueOf(k), toNode(v)));
                yield node;
            }
            case Collection<?> list -> {
                ArrayNode node = f.arrayNode();
                list.forEach(v -> node.add(toNode(v)));
                yield node;
            }
            case Boolean b -> f.booleanNode(b);
            case Integer n -> f.numberNode(n);
            case Long n -> f.numberNode(n);
            case Path p -> f.textNode(p.toAbsolutePath().toString());
            default -> f.textNode(value.toString());
        };
    }
}
