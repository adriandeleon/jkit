package dev.jkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Version;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
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

    /** Mixin for commands that support {@code --json}. */
    static final class Flag {
        @Option(names = FLAG, description = "Print one JSON document on stdout instead of text (see docs/json.md).")
        boolean json;
    }

    private JsonOut() { }

    /** A new top-level document: {@code {"schemaVersion": 1}}. */
    static ObjectNode doc() {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("schemaVersion", SCHEMA_VERSION);
        return doc;
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static void print(PrintStream out, ObjectNode doc) {
        out.println(doc.toPrettyString());
        out.flush();
    }

    /**
     * {@code {"schemaVersion": 1, "error": {"message", "hints", "exitCode"}}}, plus {@code changes}
     * when the command had already changed something before it failed.
     */
    static ObjectNode error(JkitException e, int exitCode) {
        ObjectNode doc = doc();
        if (e instanceof PartialFailure partial && !partial.changes().isEmpty()) {
            doc.set("changes", toNode(partial.changes()));
        }
        ObjectNode error = doc.putObject("error");
        error.put("message", e.getMessage());
        ArrayNode hints = error.putArray("hints");
        e.hints().forEach(hints::add);
        error.put("exitCode", exitCode);
        return doc;
    }

    /** One installed build, with where it is active. */
    static ObjectNode installed(Context ctx, Installed i) throws Exception {
        ObjectNode node = object();
        node.put("tool", i.tool().id());
        node.put("id", i.id());
        node.put("version", i.version().raw());
        node.put("major", major(i.tool(), i.version()));
        node.put("vendor", i.vendor() == null ? null : i.vendor().name());
        node.put("fx", i.fx());
        node.put("graalvm", i.vendor() != null && i.vendor().isGraalVmBased());
        node.put("preRelease", i.version().isPreRelease());
        node.put("home", i.home().toAbsolutePath().toString());
        // checked against a sha256/384/512 checksum when it was installed (see docs/json.md)
        node.put("verified", dev.jkit.core.Store.verified(i.home()));
        ArrayNode global = node.putArray("global");
        ArrayNode shell = node.putArray("activeInShell");
        for (Tool t : Tool.values()) {
            if (t.storeTool() != i.tool()) {
                continue;
            }
            Path link = ctx.os.readLink(ctx.home.global(t));
            if (Resolve.same(link, i.home())) {
                global.add(t.id());
            }
            String state = ctx.env.get(ShellEnv.stateVariable(t));
            if (state != null && Resolve.same(Path.of(state), i.home())) {
                shell.add(t.id());
            }
        }
        return node;
    }

    static ArrayNode installedList(Context ctx, ArrayNode into, List<Installed> list) throws Exception {
        for (Installed i : list) {
            into.add(installed(ctx, i));
        }
        return into;
    }

    /** A top-level document as a map, {@code schemaVersion} first; {@link #write} turns it into JSON. */
    static java.util.Map<String, Object> document() {
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        return doc;
    }

    /** A JSON object as a map with fields in the order given: key, value, key, value, … */
    static java.util.Map<String, Object> fields(Object... keysAndValues) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** Maps, collections, Jackson nodes, strings, numbers, booleans, paths (absolute) and null, pretty-printed. */
    static String write(Object value) {
        return toNode(value).toPrettyString();
    }

    private static com.fasterxml.jackson.databind.JsonNode toNode(Object value) {
        var f = MAPPER.getNodeFactory();
        return switch (value) {
            case null -> f.nullNode();
            case com.fasterxml.jackson.databind.JsonNode n -> n;
            case java.util.Map<?, ?> map -> {
                ObjectNode node = f.objectNode();
                map.forEach((k, v) -> node.set(String.valueOf(k), toNode(v)));
                yield node;
            }
            case java.util.Collection<?> list -> {
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

    /** The feature release: 21 for {@code 21.0.9+10}, 8 for {@code 1.8.0_472}, 3 for Maven 3.9.11. */
    static int major(Tool tool, Version v) {
        return tool.storeTool() == Tool.JDK && v.part(0) == 1 ? v.part(1) : v.part(0);
    }
}
