package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;
    Path home;
    Path project;
    Map<String, String> env;
    Path temurin21;
    Path zulu17;
    Path graal25;

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("user/.jkit");
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        zulu17 = install("jdk", "17.0.13+11-zulu", "version=17.0.13+11\nvendor=zulu\n");
        graal25 = install("jdk", "25.0.1+8-graalvm-ce", "version=25.0.1+8\nvendor=graalvm_community\n");
        install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), zulu17);
        Files.createSymbolicLink(home.resolve("global/maven"), home.resolve("tools/maven/3.9.11"));
        env = new HashMap<>(Map.of("JKIT_HOME", home.toString(), "HOME", tmp.resolve("user").toString(),
                "JKIT_JDK_VENDOR", "temurin",
                "PATH", String.join(File.pathSeparator, home.resolve("global/jdk/bin").toString(),
                        zulu17.resolve("bin").toString(), "/usr/bin")));
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    private EnvCommand.Result compute(boolean global) throws Exception {
        return EnvCommand.compute(Context.forEnv(env), project, global);
    }

    @Test
    void globalLinksWithoutAProject() throws Exception {
        EnvCommand.Result result = compute(false);
        assertThat(result.variables()).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString())
                .containsEntry("MAVEN_HOME", home.resolve("global/maven").toString())
                .doesNotContainKey("GRADLE_HOME");
        assertThat(result.variables().get("PATH")).isEqualTo(String.join(File.pathSeparator,
                home.resolve("global/jdk/bin").toString(), home.resolve("global/maven/bin").toString(), "/usr/bin"));
    }

    @Test
    void projectPinsOverrideGlobalsAndShellChoicesDontCount() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        env.put(ShellEnv.stateVariable(Tool.JDK), zulu17.toString());
        EnvCommand.Result result = compute(false);
        assertThat(result.variables()).containsEntry("JAVA_HOME", temurin21.toString())
                .containsEntry("MAVEN_HOME", home.resolve("global/maven").toString());
        assertThat(result.variables().get("PATH")).startsWith(temurin21.resolve("bin") + File.pathSeparator)
                .doesNotContain(zulu17.toString());
    }

    @Test
    void globalFlagIgnoresTheProject() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        assertThat(compute(true).variables()).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString());
        assertThat(compute(true).project()).isEmpty();
    }

    @Test
    void aPinWithNoInstallIsExitThreeNamingEveryMissingTool() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"11\"\ngradle = \"8\"\n");
        assertThatThrownBy(() -> compute(false))
                .isInstanceOfSatisfying(JkitException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(JkitException.NOT_INSTALLED);
                    assertThat(e.getMessage()).isEqualTo("jkit.toml wants jdk 11, gradle 8, not installed");
                    assertThat(e.hints()).containsExactly("install it: jkit install jdk@11",
                            "install it: jkit install gradle@8", "or ignore the project file: jkit env --global");
                });
    }

    @Test
    void brokenProjectFileIsAnError() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools\n");
        assertThatThrownBy(() -> compute(false)).hasMessageContaining("invalid TOML");
    }

    @Test
    void graalVmJdkAlsoSetsGraalVmHome() throws Exception {
        Files.writeString(project.resolve(".sdkmanrc"), "java=25.0.1-graalce\n");
        assertThat(compute(false).variables()).containsEntry("JAVA_HOME", graal25.toString())
                .containsEntry("GRAALVM_HOME", graal25.toString());
    }

    @Test
    void shellSyntaxAndJson() throws Exception {
        EnvCommand.Result result = compute(false);
        assertThat(ShellEnv.format(result.variables(), "fish")).startsWith("set -gx JAVA_HOME '" + home.resolve("global/jdk"));
        JsonNode doc = JSON.readTree(JsonOut.write(EnvCommand.json(result)));
        assertThat(doc.path("dir").asText()).isEqualTo(project.toString());
        assertThat(doc.path("project").isNull()).isTrue();
        assertThat(doc.path("variables").path("JAVA_HOME").asText()).isEqualTo(home.resolve("global/jdk").toString());
        assertThat(doc.path("variables").has("PATH")).isFalse();
        assertThat(doc.path("pathPrepend").get(0).asText()).isEqualTo(home.resolve("global/jdk/bin").toString());
        assertThat(doc.path("path").asText()).endsWith("/usr/bin");
    }
}
