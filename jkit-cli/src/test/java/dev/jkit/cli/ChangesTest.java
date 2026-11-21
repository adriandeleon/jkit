package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangesTest {
    @TempDir
    Path home;

    private Context context(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(extra);
        env.put("JKIT_HOME", home.toString());
        return Context.forEnv(env);
    }

    private static String print(Changes changes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        changes.print(new PrintStream(bytes, true));
        return bytes.toString();
    }

    @Test
    void reportsOnlyVariablesThatChangeAndPathEntriesAddedOrRemoved() {
        String old = "/j/tools/jdk/21/bin";
        String now = "/j/tools/jdk/25/bin";
        Context ctx = context(Map.of("PATH", old + File.pathSeparator + "/usr/bin",
                "JAVA_HOME", "/j/tools/jdk/21", "MAVEN_HOME", "/m"));
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("JAVA_HOME", "/j/tools/jdk/25");
        updates.put("MAVEN_HOME", "/m");
        updates.put("JKIT_GRAALVM_FOLLOWS_JDK", null);
        updates.put("GRADLE_HOME", null);
        updates.put("PATH", now + File.pathSeparator + "/usr/bin");

        Changes changes = new Changes(ctx).env(updates);

        assertThat(changes.json()).hasSize(3);
        assertThat(print(changes)).isEqualTo("""
                  set    JAVA_HOME=/j/tools/jdk/25
                  PATH   + %s
                  PATH   - %s
                """.formatted(now, old));
    }

    @Test
    void unsetIsReportedWhenTheVariableWasSet() {
        Context ctx = context(Map.of("JKIT_GRAALVM_FOLLOWS_JDK", "1"));
        Map<String, String> updates = new HashMap<>();
        updates.put("JKIT_GRAALVM_FOLLOWS_JDK", null);
        assertThat(print(new Changes(ctx).env(updates))).isEqualTo("  unset  JKIT_GRAALVM_FOLLOWS_JDK\n");
    }

    @Test
    void globalEffectWarnsAboutAShellChoice() {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JKIT_SHELL_JDK", "/x/jdk"));
        String out = print(new Changes(ctx).globalEffect(Tool.JDK));
        assertThat(out).contains("new shells with the jkit hook: JAVA_HOME=").contains("PATH includes")
                .contains("this shell keeps its own jdk").contains("jkit reset jdk");
    }

    @Test
    void globalEffectSaysThisShellFollowsTheLink() {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JAVA_HOME", home.resolve("global/jdk").toString()));
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK))).contains("this shell follows it already");
    }

    @Test
    void globalEffectWarnsAboutAHomeVariableSetOutsideJkit() {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JAVA_HOME", "/usr/lib/jvm/java-17"));
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK)))
                .contains("warning: JAVA_HOME=/usr/lib/jvm/java-17 is set outside jkit");
    }

    @Test
    void globalEffectWithoutTheHook() {
        Context ctx = context(Map.of());
        String out = print(new Changes(ctx).globalEffect(Tool.GRAALVM));
        assertThat(out).doesNotContain("PATH includes").contains("the jkit hook is not loaded");
    }
}
