package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
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
        return bytes.toString().replace("\r\n", "\n"); // println's line ends are \r\n on Windows
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
        Context ctx = context(Map.of("GRADLE_HOME", "/g"));
        Map<String, String> updates = new HashMap<>();
        updates.put("GRADLE_HOME", null);
        assertThat(print(new Changes(ctx).env(updates))).isEqualTo("  unset  GRADLE_HOME\n");
    }

    @Test
    void jkitsOwnVariablesAreOnlyInTheJson() {
        Context ctx = context(Map.of("JKIT_GRAALVM_FOLLOWS_JDK", "1", "PATH", "/usr/bin"));
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("JKIT_GRAALVM_FOLLOWS_JDK", null);
        updates.put("JKIT_PROJECT", "/p/jkit.toml:1795667886909");
        updates.put("JKIT_PROJECT_JDK", "/j/tools/jdk/21");
        updates.put("JAVA_HOME", "/j/tools/jdk/21");
        updates.put("PATH", "/j/tools/jdk/21/bin" + File.pathSeparator + "/usr/bin");

        Changes changes = new Changes(ctx).env(updates);

        assertThat(print(changes)).isEqualTo("""
                  set    JAVA_HOME=/j/tools/jdk/21
                  PATH   + /j/tools/jdk/21/bin
                """);
        assertThat(changes.json()).hasSize(5);
        assertThat(changes.isEmpty()).isFalse();
        Map<String, String> onlyJkits = new HashMap<>();
        onlyJkits.put("JKIT_GRAALVM_FOLLOWS_JDK", null);
        assertThat(new Changes(ctx).env(onlyJkits).isEmpty()).isTrue();
    }

    @Test
    void globalEffectWarnsAboutAShellChoice() throws Exception {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JKIT_SHELL_JDK", "/x/jdk"));
        String out = print(new Changes(ctx).globalEffect(Tool.JDK));
        assertThat(out).contains("new shells with the jkit hook: JAVA_HOME=").contains("PATH includes")
                .contains("this shell keeps its own jdk").contains("jkit reset jdk");
    }

    @Test
    void globalEffectSaysThisShellFollowsTheLink() throws Exception {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JAVA_HOME", home.resolve("global/jdk").toString()));
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK))).contains("this shell follows it already");
    }

    @Test
    void globalEffectWarnsAboutAHomeVariableSetOutsideJkit() throws Exception {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JAVA_HOME", "/usr/lib/jvm/java-17"));
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK)))
                .contains("warning: JAVA_HOME=/usr/lib/jvm/java-17 is set outside jkit");
    }

    @Test
    void globalEffectWithoutTheHook() throws Exception {
        Context ctx = context(Map.of());
        String out = print(new Changes(ctx).globalEffect(Tool.GRAALVM));
        assertThat(out).doesNotContain("PATH includes").contains("the jkit hook is not loaded");
    }

    @Test
    void aFirstInstallSetsTheUnsetHomeInAShellWithTheHook() throws Exception {
        Path link = Files.createDirectories(home.resolve("global")).resolve("jdk");
        Files.createSymbolicLink(link, Files.createDirectories(home.resolve("tools/jdk/21")));
        Path envFile = home.resolve("env.sh");
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JKIT_ENV_FILE", envFile.toString(),
                "PATH", link.resolve("bin") + File.pathSeparator + "/usr/bin"));

        String out = print(new Changes(ctx).globalEffect(Tool.JDK));

        // the hook loaded before there was a link, so it set no JAVA_HOME: jkit sets it now, and says so
        assertThat(out).contains("set    JAVA_HOME=" + link).doesNotContain("PATH   +").doesNotContain("jkit reset");
        assertThat(Files.readString(envFile)).startsWith("export JAVA_HOME=").contains(link.toString())
                .doesNotContain("PATH");
    }

    @Test
    void withTheHookInstalledButNotLoadedTheHintIsANewShell() throws Exception {
        Path user = Files.createDirectories(home.resolve("user"));
        Context ctx = context(Map.of("HOME", user.toString(), "SHELL", "/bin/bash"));
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK))).contains("load it in new shells: jkit hook --install");

        Files.writeString(user.resolve(".bashrc"), "alias ll='ls -l'\n");
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK))).contains("load it in new shells: jkit hook --install");

        Files.writeString(user.resolve(".bashrc"), String.join("\n", dev.jkit.core.ShellRc.block("bash")) + "\n");
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK)))
                .contains("the jkit hook is not loaded in this shell, so nothing sets JAVA_HOME; ")
                .contains(".bashrc loads it, so open a new shell").doesNotContain("jkit hook --install");
    }

    @Test
    void anUnsetHomeWithoutTheHooksFileGetsAHintThatWorks() throws Exception {
        Context ctx = context(Map.of("JKIT_SHELL", "bash"));
        String out = print(new Changes(ctx).globalEffect(Tool.JDK));
        assertThat(out).contains("JAVA_HOME is not set in this shell; open a new shell, or: eval \"$(jkit env)\"")
                .doesNotContain("jkit reset");
    }

    @Test
    void globalEffectInAProjectSaysTheProjectsVersionStays() throws Exception {
        Context ctx = context(Map.of("JKIT_SHELL", "bash", "JKIT_PROJECT_JDK", "/x/jdk", "JAVA_HOME", "/x/jdk"));
        assertThat(print(new Changes(ctx).globalEffect(Tool.JDK)))
                .contains("this shell keeps the project's jdk").doesNotContain("set outside jkit");
    }

    @Test
    void foldersAndCachedListsAreReported() throws Exception {
        Path jkit = home.resolve("fresh");
        Map<String, String> env = new HashMap<>(Map.of("JKIT_HOME", jkit.toString()));
        Context ctx = Context.forEnv(env);
        Files.createDirectories(jkit); // the command made the home
        Path install = jkit.resolve("tools/jdk/21.0.9+10-temurin");
        ctx.cacheWrites.add(new Context.CacheWrite(URI.create("https://api.foojay.io/a"), jkit.resolve("index/a.cache"), true));
        ctx.cacheWrites.add(new Context.CacheWrite(URI.create("https://api.foojay.io/b"), jkit.resolve("index/b.cache"), false));

        Changes changes = new Changes(ctx).disk().folder(install, true);

        assertThat(print(changes)).isEqualTo("""
                  folder %s created
                  folder %s created
                  cache  %s: 1 file saved, 1 file refreshed from api.foojay.io
                """.formatted(jkit, install, jkit.resolve("index")));
        assertThat(changes.json().stream().map(c -> String.valueOf(((Map<?, ?>) c).get("kind"))).toList())
                .containsExactly("folder", "file", "file", "folder");
        // reported once: a second Changes has nothing left to say
        assertThat(new Changes(ctx).disk().isEmpty()).isTrue();
    }

    @Test
    void installsMessageNamesTheFolderSoItsLinesAndTheCacheAreOnlyInTheJson() throws Exception {
        Path jkit = home.resolve("fresh");
        Context ctx = Context.forEnv(new HashMap<>(Map.of("JKIT_HOME", jkit.toString())));
        Files.createDirectories(jkit); // the command made the home
        Path install = jkit.resolve("tools/jdk/21.0.9+10-temurin");
        Path leftover = jkit.resolve("tools/jdk/21.0.9+10-temurin.old");
        ctx.cacheWrites.add(new Context.CacheWrite(URI.create("https://api.foojay.io/a"), jkit.resolve("index/a.cache"), true));

        Changes changes = new Changes(ctx).disk().folder(leftover, false).folder(install, true)
                .link(jkit.resolve("global/jdk"), install).namedInMessage(install);

        assertThat(print(changes)).isEqualTo("""
                  folder %s deleted
                  link   %s → %s
                """.formatted(leftover, jkit.resolve("global/jdk"), install));
        assertThat(changes.json()).hasSize(5);
    }

    @Test
    void deletedFolderIsReported() {
        Context ctx = context(Map.of());
        Path gone = home.resolve("tools/jdk/17-zulu");
        assertThat(print(new Changes(ctx).folder(gone, false))).isEqualTo("  folder " + gone + " deleted\n");
    }
}
