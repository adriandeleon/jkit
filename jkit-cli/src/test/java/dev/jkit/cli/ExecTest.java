package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ExecTest {
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
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        zulu17 = install("jdk", "17.0.13+11-zulu", "version=17.0.13+11\nvendor=zulu\n");
        install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), temurin21);
        env = new HashMap<>(Map.of("JKIT_HOME", home.toString(), "HOME", tmp.resolve("user").toString(),
                "JKIT_JDK_VENDOR", "temurin", "PATH", "/usr/bin"));
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.createDirectories(path.resolve("bin"));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    private ExecCommand.Plan plan(String... choices) throws Exception {
        return ExecCommand.plan(Context.forEnv(env), project, false, ExecCommand.choices(List.of(choices)),
                List.of("java", "-version"));
    }

    @Test
    void choicesTakeAVendorAfterATool() {
        List<ExecCommand.Choice> choices = ExecCommand.choices(List.of("jdk@17", "zulu", "maven@3.9", "gradle"));
        assertThat(choices).extracting(c -> c.spec().toString()).containsExactly("jdk@17", "maven@3.9", "gradle");
        assertThat(choices).extracting(ExecCommand.Choice::vendor).containsExactly("zulu", null, null);
    }

    @Test
    void aWordThatIsNoToolOrVendorIsAUsageError() {
        assertThatThrownBy(() -> ExecCommand.choices(List.of("mvn", "test")))
                .isInstanceOf(JkitException.class).hasMessageContaining("vendor for maven");
        assertThatThrownBy(() -> ExecCommand.choices(List.of("./build.sh")))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(2));
        assertThatThrownBy(() -> ExecCommand.choices(List.of("maven", "zulu")))
                .isInstanceOf(JkitException.class).hasMessageContaining("'zulu' is not a tool");
    }

    @Test
    void withoutToolsItIsWhatJkitEnvGives() throws Exception {
        ExecCommand.Plan plan = plan();
        assertThat(plan.variables()).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString());
        assertThat(plan.variables().get("PATH")).startsWith(home.resolve("global/jdk/bin").toString());
    }

    @Test
    void namedToolsOverrideTheProjectAndGlobals() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        ExecCommand.Plan plan = plan("jdk@17", "zulu", "maven");
        assertThat(plan.variables()).containsEntry("JAVA_HOME", zulu17.toString())
                .containsEntry("MAVEN_HOME", home.resolve("tools/maven/3.9.11").toString());
        assertThat(plan.variables().get("PATH")).isEqualTo(String.join(File.pathSeparator,
                zulu17.resolve("bin").toString(), home.resolve("tools/maven/3.9.11/bin").toString(), "/usr/bin"));
    }

    @Test
    void aToolThatIsNotInstalledExitsThree() {
        assertThatThrownBy(() -> plan("jdk@11"))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(JkitException.NOT_INSTALLED))
                .hasMessageContaining("no installed JDK matches 11");
    }

    @Test
    void theCallingShellsStateDoesNotLeakIn() throws Exception {
        env.put(ShellEnv.stateVariable(Tool.MAVEN), home.resolve("tools/maven/3.9.11").toString());
        env.put("MAVEN_HOME", home.resolve("tools/maven/3.9.11").toString());
        env.put("JKIT_ENV_FILE", tmp.resolve("env").toString());
        env.put("GRADLE_HOME", "/opt/gradle");
        Map<String, String> child = ExecCommand.childEnv(env, plan());
        assertThat(child).doesNotContainKeys(ShellEnv.stateVariable(Tool.MAVEN), "MAVEN_HOME", "JKIT_ENV_FILE")
                .containsEntry("GRADLE_HOME", "/opt/gradle") // not jkit's: left alone
                .containsEntry("HOME", tmp.resolve("user").toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theCommandIsFoundOnTheNewPath() throws Exception {
        Path java = Files.writeString(zulu17.resolve("bin/java"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        String path = zulu17.resolve("bin") + File.pathSeparator + "/usr/bin";
        assertThat(ExecCommand.executable("java", path, project)).isEqualTo(java.toString());
        assertThat(ExecCommand.executable("./run.sh", path, project)).isEqualTo("./run.sh");
        assertThatThrownBy(() -> ExecCommand.executable("no-such-command-jkit", path, project))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(127));
    }
}
