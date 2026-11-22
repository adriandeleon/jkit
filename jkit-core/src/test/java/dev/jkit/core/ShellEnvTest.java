package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellEnvTest {
    private final JkitHome home = new JkitHome(Path.of("/h/.jkit"));
    private final Path jdk17 = Path.of("/h/.jkit/tools/jdk/17.0.21+9-zulu");
    private final Path jdk21 = Path.of("/h/.jkit/tools/jdk/21.0.13+9-temurin");

    private static String path(String... entries) {
        return String.join(File.pathSeparator, entries);
    }

    @Test
    void switchingReplacesOnlyJkitEntries() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", path("/h/.jkit/global/jdk/bin", "/usr/bin"));
        Map<String, String> first = ShellEnv.use(env, home, Tool.JDK, jdk17, false);
        assertThat(first.get("PATH")).isEqualTo(path(jdk17 + "/bin", "/h/.jkit/global/jdk/bin", "/usr/bin"));
        assertThat(first.get("JAVA_HOME")).isEqualTo(jdk17.toString());

        env.putAll(first);
        Map<String, String> second = ShellEnv.use(env, home, Tool.JDK, jdk21, false);
        assertThat(second.get("PATH")).isEqualTo(path(jdk21 + "/bin", "/h/.jkit/global/jdk/bin", "/usr/bin"));
    }

    @Test
    void graalVmJdkAlsoSetsGraalVmHomeUnlessTheSlotIsSet() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        assertThat(ShellEnv.use(env, home, Tool.JDK, jdk21, true)).containsEntry("GRAALVM_HOME", jdk21.toString());

        env.put(ShellEnv.stateVariable(Tool.GRAALVM), "/somewhere/else");
        assertThat(ShellEnv.use(env, home, Tool.JDK, jdk21, true)).doesNotContainKey("GRAALVM_HOME");
    }

    @Test
    void graalVmSlotStaysOffPath() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        Map<String, String> changes = ShellEnv.use(env, home, Tool.GRAALVM, jdk21, true);
        assertThat(changes.get("PATH")).isEqualTo("/usr/bin");
        assertThat(changes.get("GRAALVM_HOME")).isEqualTo(jdk21.toString());
    }

    @Test
    void resetDropsTheShellEntry() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        env.putAll(ShellEnv.use(env, home, Tool.JDK, jdk17, false));
        Map<String, String> reset = ShellEnv.reset(env, home, Tool.JDK);
        assertThat(reset.get("PATH")).isEqualTo("/usr/bin");
        assertThat(reset).containsEntry(ShellEnv.stateVariable(Tool.JDK), null);
    }

    @Test
    void quotesForEachShell() {
        Map<String, String> changes = new java.util.LinkedHashMap<>();
        changes.put("JAVA_HOME", "/it's here");
        changes.put("OLD", null);
        assertThat(ShellEnv.format(changes, "bash")).isEqualTo("export JAVA_HOME='/it'\\''s here'\nunset OLD\n");
        assertThat(ShellEnv.format(changes, "fish")).startsWith("set -gx JAVA_HOME ");
        assertThat(ShellEnv.format(changes, "pwsh")).startsWith("$env:JAVA_HOME = ");
    }

    @Test
    void freshPutsChosenBinsFirstAndDropsOldJkitEntries() {
        Path maven = Path.of("/h/.jkit/global/maven");
        Map<String, String> env = Map.of("PATH", path("/h/.jkit/global/maven/bin", jdk17 + "/bin", "/usr/bin"));
        Map<String, String> vars = ShellEnv.fresh(env, home, Map.of(Tool.JDK, jdk21, Tool.MAVEN, maven), false);
        assertThat(vars.keySet()).containsExactly("JAVA_HOME", "MAVEN_HOME", "PATH");
        assertThat(vars.get("JAVA_HOME")).isEqualTo(jdk21.toString());
        assertThat(vars.get("PATH")).isEqualTo(path(jdk21 + "/bin", "/h/.jkit/global/maven/bin", "/usr/bin"));
        assertThat(vars).doesNotContainKey(ShellEnv.stateVariable(Tool.JDK));
    }

    @Test
    void freshGraalVmFollowsAGraalVmJdkUnlessOneIsChosen() {
        Map<String, String> env = Map.of("PATH", "/usr/bin");
        assertThat(ShellEnv.fresh(env, home, Map.of(Tool.JDK, jdk21), true)).containsEntry("GRAALVM_HOME", jdk21.toString());
        assertThat(ShellEnv.fresh(env, home, Map.of(Tool.JDK, jdk21), false)).doesNotContainKey("GRAALVM_HOME");
        assertThat(ShellEnv.fresh(env, home, Map.of(Tool.JDK, jdk21, Tool.GRAALVM, jdk17), true))
                .containsEntry("GRAALVM_HOME", jdk17.toString());
    }

    @Test
    void projectLayerSetsHomesUnlessTheShellChoseItsOwn() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", path("/h/.jkit/global/jdk/bin", "/usr/bin")));
        env.put(ShellEnv.stateVariable(Tool.MAVEN), "/h/.jkit/tools/maven/3.9.11");
        Path maven4 = Path.of("/h/.jkit/tools/maven/4.0.0");
        Map<String, String> out = ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21, Tool.MAVEN, maven4), false);
        assertThat(out).containsEntry("JKIT_PROJECT_JDK", jdk21.toString())
                .containsEntry("JAVA_HOME", jdk21.toString())
                .containsEntry("JKIT_PROJECT_MAVEN", maven4.toString())
                .doesNotContainKey("MAVEN_HOME"); // jkit use wins
        assertThat(out.get("PATH")).isEqualTo(path(jdk21 + "/bin", "/h/.jkit/tools/maven/3.9.11/bin",
                "/h/.jkit/global/jdk/bin", "/usr/bin"));
    }

    @Test
    void leavingTheProjectLayerGoesBackToGlobal() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        env.putAll(ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), true));
        assertThat(env).containsEntry("GRAALVM_HOME", jdk21.toString());

        Map<String, String> out = ShellEnv.project(env, home, Map.of(), false);
        assertThat(out).containsEntry("JKIT_PROJECT_JDK", null).containsEntry("JAVA_HOME", null)
                .containsEntry("GRAALVM_HOME", null).containsEntry("JKIT_GRAALVM_FOLLOWS_JDK", null)
                .containsEntry("PATH", "/usr/bin");
    }

    @Test
    void useOnTopOfAProjectAndResetBackToIt() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        env.putAll(ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false));
        env.putAll(ShellEnv.use(env, home, Tool.JDK, jdk17, false));
        assertThat(env.get("PATH")).isEqualTo(path(jdk17 + "/bin", "/usr/bin"));

        Map<String, String> out = ShellEnv.reset(env, home, Tool.JDK);
        assertThat(out).containsEntry("JAVA_HOME", jdk21.toString()).containsEntry("PATH", path(jdk21 + "/bin", "/usr/bin"));
    }

    @Test
    void anotherToolsUseKeepsTheProjectOnPath() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        env.putAll(ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false));
        Path maven = Path.of("/h/.jkit/tools/maven/3.9.11");
        assertThat(ShellEnv.use(env, home, Tool.MAVEN, maven, false).get("PATH"))
                .isEqualTo(path(jdk21 + "/bin", maven + "/bin", "/usr/bin"));
    }
}
