package dev.jkit.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Jackson tree model only: no data binding, so no reflection setup for native-image. */
final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() { }

    static JsonNode parse(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
