package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;
    Path home;
    Path project;
    Map<String, String> env;
    Path temurin21;
    Path zulu17;

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("user/.jkit");
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        env = new HashMap<>(Map.of("JKIT_HOME", home.toString(), "HOME", tmp.resolve("user").toString(),
                "JKIT_JDK_VENDOR", "temurin"));
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        zulu17 = install("jdk", "17.0.13+11-zulu", "version=17.0.13+11\nvendor=zulu\n");
        install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), zulu17);
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    private String text() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Context ctx = Context.forEnv(env, out);
        StatusCommand.print(ctx, StatusCommand.read(ctx, project), out);
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"); // \r\n on Windows
    }

    private JsonNode json() throws Exception {
        Context ctx = Context.forEnv(env);
        return JSON.readTree(JsonOut.write(StatusCommand.json(ctx, StatusCommand.read(ctx, project))));
    }

    @Test
    void globalJdkAndAnInstalledButUnchosenMaven() throws Exception {
        env.put("JKIT_SHELL", "bash");
        env.put("JAVA_HOME", home.resolve("global/jdk").toString());
        assertThat(text()).isEqualTo("""
                jdk      17.0.13+11 zulu         global
                maven    -                       not set
                         1 installed, none chosen: jkit use maven@3.9.11 --global
                """);
    }

    @Test
    void projectPinNotAppliedSaysHowToUseIt() throws Exception {
        env.put("JKIT_SHELL", "bash");
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\nmaven = \"4.0\"\nkotlin = \"2\"\n");
        env.put("JAVA_HOME", home.resolve("global/jdk").toString());
        String out = text();
        assertThat(out).contains("jdk      17.0.13+11 zulu         global\n"
                + "         jkit.toml wants jdk 21.0.9+10 temurin; this shell has global: jkit sync\n");
        assertThat(out).contains("maven    -                       not set\n"
                + "         jkit.toml wants maven 4.0, not installed: jkit sync\n");
        assertThat(out).contains("project: " + project + " (jkit.toml)\n  warning: ");
        assertThat(out).contains("unknown tool 'kotlin'");
    }

    @Test
    void projectPinApplied() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        env.put("JAVA_HOME", temurin21.toString());
        assertThat(text()).startsWith("jdk      21.0.9+10 temurin       project   jkit.toml (jdk 21)\n");
    }

    @Test
    void outsideJavaHomeIsNamedWithItsReleaseVersion() throws Exception {
        Path system = Files.createDirectories(tmp.resolve("usr/lib/jvm/java-21"));
        Files.writeString(system.resolve("release"), "JAVA_VERSION=\"21.0.13\"\n");
        env.put("JAVA_HOME", system.toString());
        assertThat(text()).startsWith("jdk      21.0.13                 outside   JAVA_HOME=" + system + "\n"
                + "         JAVA_HOME is set outside jkit and wins over the global jdk; remove it from your shell profile to use jkit's\n");
    }

    @Test
    void missingHookIsSaidOnceWhenTheGlobalsAreNotApplied() throws Exception {
        Files.createSymbolicLink(home.resolve("global/maven"), home.resolve("tools/maven/3.9.11"));
        assertThat(text()).isEqualTo("""
                jdk      -                       not set
                maven    -                       not set
                the global jdk, maven aren't applied: the jkit hook isn't loaded; load it in new shells: %s
                """.formatted(Changes.HOOK_SETUP));
    }

    @Test
    void anInstalledPinWithoutTheHookSaysHowToUseItNotSync() throws Exception {
        // jkit sync can't change a shell without the hook: pointing there again would go in circles
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        assertThat(text()).startsWith("jdk      -                       not set\n"
                + "         jkit.toml wants jdk 21.0.9+10 temurin; this shell has none and no jkit hook to switch it: "
                + "run one command with it: jkit exec -- <command>, or load it in new shells: " + Changes.HOOK_SETUP + "\n");
    }

    @Test
    void jsonDescribesEachTool() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = { version = \"21\", vendor = \"temurin\" }\n");
        env.put("JAVA_HOME", home.resolve("global/jdk").toString());
        JsonNode doc = json();
        assertThat(doc.path("schemaVersion").asInt()).isEqualTo(JsonOut.SCHEMA_VERSION);
        assertThat(doc.path("project").path("dir").asText()).isEqualTo(project.toString());
        assertThat(doc.path("projectError").isNull()).isTrue();
        JsonNode jdk = doc.path("tools").get(0);
        assertThat(jdk.path("tool").asText()).isEqualTo("jdk");
        assertThat(jdk.path("source").asText()).isEqualTo("global");
        assertThat(jdk.path("installed").path("id").asText()).isEqualTo("17.0.13+11-zulu");
        assertThat(jdk.path("pin").path("vendor").asText()).isEqualTo("temurin");
        assertThat(jdk.path("pinInstalled").path("home").asText()).isEqualTo(temurin21.toString());
        assertThat(jdk.path("wanted").path("source").asText()).isEqualTo("project");
        assertThat(jdk.path("global").path("missing").asBoolean()).isFalse();
        assertThat(jdk.path("matches").asBoolean()).isFalse();
        assertThat(jdk.path("hint").asText()).contains("jkit exec -- <command>");
        assertThat(doc.path("tools")).hasSize(5);
    }
}
