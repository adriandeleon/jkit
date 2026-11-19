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
}
