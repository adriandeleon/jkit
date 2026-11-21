package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jkit.model.JkitException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonOutputTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private Path install(String storeDir, String dirName, String properties) throws Exception {
        Path dir = home.resolve("tools").resolve(storeDir).resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jkit-install.properties"), properties);
        return dir;
    }

    @Test
    void listDescribesEveryInstallAndTheGlobalLinks() throws Exception {
        Path temurin = install("jdk", "21.0.9+10-temurin", "tool=jdk\nversion=21.0.9+10\nvendor=temurin\nfx=false\n");
        install("jdk", "1.8.0_472-zulu-fx", "tool=jdk\nversion=1.8.0_472\nvendor=zulu\nfx=true\n");
        install("maven", "3.9.11", "tool=maven\nversion=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global").resolve("jdk"), Path.of("../tools/jdk/21.0.9+10-temurin"));
        Context ctx = Context.forEnv(Map.of("JKIT_HOME", home.toString(), "JKIT_SHELL_JDK", temurin.toString()));

        JsonNode doc = JSON.readTree(JsonOut.write(ListCommand.json(ctx)));

        assertThat(doc.path("schemaVersion").asInt()).isEqualTo(JsonOut.SCHEMA_VERSION);
        assertThat(doc.path("home").asText()).isEqualTo(home.toAbsolutePath().toString());
        assertThat(doc.path("global").path("jdk").asText()).isEqualTo(temurin.toString());
        assertThat(doc.path("global").path("gradle").isNull()).isTrue();
        JsonNode installed = doc.path("installed");
        assertThat(installed).hasSize(3);
        JsonNode first = installed.get(0);
        assertThat(first.path("id").asText()).isEqualTo("21.0.9+10-temurin");
        assertThat(first.path("major").asInt()).isEqualTo(21);
        assertThat(first.path("vendor").asText()).isEqualTo("temurin");
        assertThat(first.path("home").asText()).isEqualTo(temurin.toString());
        assertThat(first.path("global").toString()).isEqualTo("[\"jdk\"]");
        assertThat(first.path("activeInShell").toString()).isEqualTo("[\"jdk\"]");
        JsonNode legacy = installed.get(1);
        assertThat(legacy.path("major").asInt()).isEqualTo(8);
        assertThat(legacy.path("fx").asBoolean()).isTrue();
        JsonNode maven = installed.get(2);
        assertThat(maven.path("tool").asText()).isEqualTo("maven");
        assertThat(maven.path("vendor").isNull()).isTrue();
    }

    @Test
    void writeKeepsFieldOrderAndEscapes() throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("text", "a \"quoted\"\nline");
        doc.put("list", List.of(1, true, Path.of("/x")));
        doc.put("none", null);

        String out = JsonOut.write(doc);

        assertThat(out.indexOf("schemaVersion")).isLessThan(out.indexOf("text"));
        JsonNode read = JSON.readTree(out);
        assertThat(read.path("text").asText()).isEqualTo("a \"quoted\"\nline");
        assertThat(read.path("list").toString()).isEqualTo("[1,true,\"/x\"]");
        assertThat(read.path("none").isNull()).isTrue();
    }

    @Test
    void errorsAreOneDocumentWithMessageHintsAndExitCode() throws Exception {
        JsonNode doc = JSON.readTree(JsonOut.error(new JkitException(3, "no match", "install it"), 3).toString());

        assertThat(doc.path("error").path("message").asText()).isEqualTo("no match");
        assertThat(doc.path("error").path("hints").toString()).isEqualTo("[\"install it\"]");
        assertThat(doc.path("error").path("exitCode").asInt()).isEqualTo(3);
    }
}
