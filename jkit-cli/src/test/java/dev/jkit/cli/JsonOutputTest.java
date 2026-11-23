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
        // Windows reads a link back with long folder names (runneradmin, not RUNNER~1): same folder
        assertThat(Path.of(doc.path("global").path("jdk").asText()).toRealPath()).isEqualTo(temurin.toRealPath());
        assertThat(doc.path("global").path("gradle").isNull()).isTrue();
        JsonNode installed = doc.path("installed");
        assertThat(installed).hasSize(3);
        JsonNode first = installed.get(0);
        assertThat(first.path("id").asText()).isEqualTo("21.0.9+10-temurin");
        assertThat(first.path("major").asInt()).isEqualTo(21);
        assertThat(first.path("vendor").asText()).isEqualTo("temurin");
        // the same folder; on Windows one side may come back with its long name (runneradmin, not RUNNER~1)
        assertThat(Path.of(first.path("home").asText()).toRealPath()).isEqualTo(temurin.toRealPath());
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
        // paths are written absolute: /x on Unix, D:\x (the current drive) on Windows
        assertThat(read.path("list").get(2).asText()).isEqualTo(Path.of("/x").toAbsolutePath().toString());
        assertThat(read.path("list").get(0).asInt()).isEqualTo(1);
        assertThat(read.path("list").get(1).asBoolean()).isTrue();
        assertThat(read.path("none").isNull()).isTrue();
    }

    @Test
    void controlCharactersAreJsonEscapesNotTerminalCodes() throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("text", "a\u001b[2Jb\u007fc\u009bd");
        String out = JsonOut.write(doc);
        assertThat(out).doesNotContain("\u001b").doesNotContain("\u007f").doesNotContain("\u009b")
                .contains("\\u001B").contains("\\u007F").contains("\\u009b");
        assertThat(JSON.readTree(out).path("text").asText()).isEqualTo("a\u001b[2Jb\u007fc\u009bd");
    }

    @Test
    void aFailureAfterChangesStillListsThem() throws Exception {
        // sync installed one pin, then the next download failed: the new folder must not go unreported
        List<Object> changes = List.of(JsonOut.fields("kind", "folder", "name", "/h/tools/jdk/21", "action", "create",
                "value", null));
        JsonNode doc = new ObjectMapper().readTree(JsonOut.error(
                new PartialFailure(new JkitException("can't reach repo.maven.apache.org"), changes), 1).toString());
        assertThat(doc.path("error").path("message").asText()).isEqualTo("can't reach repo.maven.apache.org");
        assertThat(doc.path("changes").get(0).path("name").asText()).isEqualTo("/h/tools/jdk/21");
        assertThat(new ObjectMapper().readTree(JsonOut.error(new JkitException("x"), 1).toString()).has("changes"))
                .isFalse();
    }

    @Test
    void errorsAreOneDocumentWithMessageHintsAndExitCode() throws Exception {
        JsonNode doc = JSON.readTree(JsonOut.error(new JkitException(3, "no match", "install it"), 3).toString());

        assertThat(doc.path("error").path("message").asText()).isEqualTo("no match");
        assertThat(doc.path("error").path("hints").toString()).isEqualTo("[\"install it\"]");
        assertThat(doc.path("error").path("exitCode").asInt()).isEqualTo(3);
    }
}
