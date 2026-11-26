package dev.jkit.sources;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jkit.model.JkitException;
import java.io.IOException;
import java.net.URI;

/** Jackson tree model only: no data binding, so no reflection setup for native-image. */
final class Json {
    /**
     * Limits for answers from a source or mirror: a few megabytes of {@code [[],[],…]} would
     * otherwise become millions of tree nodes. foojay's largest answer has about 50,000 tokens.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64)
                    .maxTokenCount(1_000_000)
                    .maxStringLength(1 << 20)
                    .build())
            .build());

    private Json() { }

    static JsonNode parse(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    /**
     * Fetches and parses {@code uri}. An answer that isn't usable JSON is an error about the source,
     * not a jkit bug, and is dropped from the cache so the next run asks again.
     */
    static JsonNode fetch(Http http, URI uri) throws IOException {
        String body = http.get(uri);
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null) {
                throw new IOException("empty");
            }
            return node;
        } catch (IOException | RuntimeException e) {
            http.evict(uri);
            throw new JkitException(uri.getHost() + " sent data jkit can't read",
                    "try again later, or with --refresh; check JKIT_SOURCES_*_URL if you use a mirror");
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
