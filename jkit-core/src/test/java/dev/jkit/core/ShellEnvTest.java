package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellEnvTest {
    private final JkitHome home = new JkitHome(Path.of("/h/.jkit"));
    private final Path jdk17 = Path.of("/h/.jkit/tools/jdk/17.0.21+9-zulu");
    private final Path jdk21 = Path.of("/h/.jkit/tools/jdk/21.0.13+9-temurin");

    /** A path in this OS's spelling, as PATH holds it there (\\h\\.jkit\\... on Windows). */
    private static String p(String unixPath) {
        return Path.of(unixPath).toString();
    }

    private static String bin(Path home) {
        return home.resolve("bin").toString();
    }

    private static String path(String... entries) {
        return String.join(File.pathSeparator, entries);
    }

    @Test
    void switchingReplacesOnlyJkitEntries() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", path(p("/h/.jkit/global/jdk/bin"), "/usr/bin"));
        Map<String, String> first = ShellEnv.use(env, home, Tool.JDK, jdk17, false);
        assertThat(first.get("PATH")).isEqualTo(path(bin(jdk17), p("/h/.jkit/global/jdk/bin"), "/usr/bin"));
        assertThat(first.get("JAVA_HOME")).isEqualTo(jdk17.toString());

        env.putAll(first);
        Map<String, String> second = ShellEnv.use(env, home, Tool.JDK, jdk21, false);
        assertThat(second.get("PATH")).isEqualTo(path(bin(jdk21), p("/h/.jkit/global/jdk/bin"), "/usr/bin"));
    }

    /** Applies {@code changes} to {@code env}, as the shell would. */
    private static void apply(Map<String, String> env, Map<String, String> changes) {
        changes.forEach((k, v) -> {
            if (v == null) {
                env.remove(k);
            } else {
                env.put(k, v);
            }
        });
    }

    @Test
    void aJavaHomeSetOutsideJkitIsKeptAndComesBackOnReset() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/usr/lib/jvm/mine"));
        Map<String, String> use = ShellEnv.use(env, home, Tool.JDK, jdk17, false);
        assertThat(use).containsEntry("JKIT_USER_JDK", "/usr/lib/jvm/mine").containsEntry("JAVA_HOME", jdk17.toString());
        apply(env, use);

        // a second jkit use doesn't take jkit's own value for the user's
        Map<String, String> again = ShellEnv.use(env, home, Tool.JDK, jdk21, false);
        assertThat(again).doesNotContainKey("JKIT_USER_JDK");
        apply(env, again);

        Map<String, String> reset = ShellEnv.reset(env, home, Tool.JDK);
        assertThat(reset).containsEntry("JAVA_HOME", "/usr/lib/jvm/mine").containsEntry("JKIT_USER_JDK", null);
        assertThat(reset.get("PATH")).isEqualTo("/usr/bin");
    }

    @Test
    void aProjectKeepsTheUsersJavaHomeUntilItLeavesAndAShellChoiceInBetweenChangesNothing() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/opt/jdk", "MAVEN_HOME", ""));
        Map<String, String> enter = ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false, "k");
        assertThat(enter).containsEntry("JKIT_USER_JDK", "/opt/jdk").doesNotContainKey("JKIT_USER_MAVEN");
        apply(env, enter);
        apply(env, ShellEnv.use(env, home, Tool.JDK, jdk17, false));
        assertThat(env).containsEntry("JKIT_USER_JDK", "/opt/jdk");
        apply(env, ShellEnv.reset(env, home, Tool.JDK)); // the project's applies again
        assertThat(env).containsEntry("JAVA_HOME", jdk21.toString()).containsEntry("JKIT_USER_JDK", "/opt/jdk");

        Map<String, String> leave = ShellEnv.project(env, home, Map.of(), false, null);
        assertThat(leave).containsEntry("JAVA_HOME", "/opt/jdk").containsEntry("JKIT_USER_JDK", null);
        assertThat(leave.get("PATH")).isEqualTo("/usr/bin");
    }

    @Test
    void pathEntriesAreComparedAsFolders(@TempDir Path tmp) {
        String bin = tmp.resolve("jdk/bin").toString();
        assertThat(ShellEnv.samePathEntry(bin, bin + File.separator)).isTrue();
        assertThat(ShellEnv.samePathEntry(bin, tmp.resolve("jdk/./bin").toString())).isTrue();
        assertThat(ShellEnv.samePathEntry(bin, tmp.resolve("jdk/bin2").toString())).isFalse();
        if (File.separatorChar == '\\') {
            assertThat(ShellEnv.samePathEntry(bin, bin.toUpperCase(Locale.ROOT).replace('\\', '/'))).isTrue();
        }
    }

    @Test
    void theUsersOwnJavaHomeComesBackWithItsBinFirstOnPath(@TempDir Path tmp) throws Exception {
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        String global = p("/h/.jkit/global/jdk/bin");
        // the hook put the global bin on PATH before the user's JAVA_HOME was set
        Map<String, String> env = new HashMap<>(Map.of("PATH", path(global, "/usr/bin"), "JAVA_HOME", mine.toString()));
        apply(env, ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false, "k"));
        assertThat(env.get("PATH")).isEqualTo(path(bin(jdk21), global, "/usr/bin"));

        Map<String, String> leave = ShellEnv.project(env, home, Map.of(), false, null);
        assertThat(leave).containsEntry("JAVA_HOME", mine.toString());
        // java is $JAVA_HOME/bin/java again; only jkit's entries went
        assertThat(leave.get("PATH")).isEqualTo(path(bin(mine), "/usr/bin"));
        apply(env, leave);

        apply(env, ShellEnv.use(env, home, Tool.JDK, jdk17, false));
        assertThat(env.get("PATH")).isEqualTo(path(bin(jdk17), bin(mine), "/usr/bin"));
        Map<String, String> reset = ShellEnv.reset(env, home, Tool.JDK);
        assertThat(reset).containsEntry("JAVA_HOME", mine.toString());
        assertThat(reset.get("PATH")).isEqualTo(path(bin(mine), "/usr/bin")); // already there: not twice
    }

    @Test
    void theUsersOwnJavaHomeInsideToolsKeepsItsBinAfterReset(@TempDir Path tmp) throws Exception {
        JkitHome own = new JkitHome(tmp.resolve(".jkit"));
        Path mine = Files.createDirectories(own.toolsRoot().resolve("jdk/21/bin")).getParent();
        Path jdk17 = Files.createDirectories(own.toolsRoot().resolve("jdk/17"));
        // the user exported JAVA_HOME to an install of jkit's, and the hook put its bin first
        Map<String, String> env = new HashMap<>(Map.of("PATH", path(bin(mine), "/usr/bin"), "JAVA_HOME", mine.toString()));
        apply(env, ShellEnv.use(env, own, Tool.JDK, jdk17, false));
        assertThat(env).containsEntry("JKIT_USER_JDK", mine.toString());

        Map<String, String> reset = ShellEnv.reset(env, own, Tool.JDK);
        assertThat(reset).containsEntry("JAVA_HOME", mine.toString());
        assertThat(reset.get("PATH")).isEqualTo(path(bin(mine), "/usr/bin"));
        apply(env, reset);

        // the same when a project leaves
        apply(env, ShellEnv.project(env, own, Map.of(Tool.JDK, jdk17), false, "k"));
        Map<String, String> leave = ShellEnv.project(env, own, Map.of(), false, null);
        assertThat(leave).containsEntry("JAVA_HOME", mine.toString());
        assertThat(leave.get("PATH")).isEqualTo(path(bin(mine), "/usr/bin"));
        apply(env, leave);

        // and when jkit use chose the very install the user's JAVA_HOME names
        apply(env, ShellEnv.use(env, own, Tool.JDK, mine, false));
        Map<String, String> same = ShellEnv.reset(env, own, Tool.JDK);
        assertThat(same).containsEntry("JAVA_HOME", mine.toString());
        assertThat(same.get("PATH")).isEqualTo(path(bin(mine), "/usr/bin"));
    }

    @Test
    void aJavaHomeChangedByHandInAProjectStaysWhenTheProjectLeaves(@TempDir Path tmp) throws Exception {
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        Path other = Files.createDirectories(tmp.resolve("other-jdk/bin")).getParent();
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "JAVA_HOME", other.toString()));
        apply(env, ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false, "k"));
        assertThat(env).containsEntry("JKIT_USER_JDK", other.toString());
        // export JAVA_HOME=... PATH=$JAVA_HOME/bin:$PATH, inside the project
        env.put("JAVA_HOME", mine.toString());
        env.put("PATH", path(bin(mine), env.get("PATH")));
        assertThat(ShellEnv.changedByHand(env, Tool.JDK)).isTrue();

        Map<String, String> leave = ShellEnv.project(env, home, Map.of(), false, null);
        assertThat(leave).doesNotContainKey("JAVA_HOME").containsEntry("JKIT_USER_JDK", null)
                .containsEntry("JKIT_PROJECT_JDK", null);
        assertThat(leave.get("PATH")).isEqualTo(path(bin(mine), "/usr/bin"));
    }

    @Test
    void aJavaHomeChangedByHandIsKeptWhenAnotherProjectApplies(@TempDir Path tmp) throws Exception {
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        apply(env, ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false, "a"));
        env.put("JAVA_HOME", mine.toString());
        Map<String, String> next = ShellEnv.project(env, home, Map.of(Tool.JDK, jdk17), false, "b");
        assertThat(next).containsEntry("JKIT_USER_JDK", mine.toString()).containsEntry("JAVA_HOME", jdk17.toString());
    }

    @Test
    void aJavaHomeWithATrailingSlashIsNotPutOnPathTwice(@TempDir Path tmp) throws Exception {
        Path mine = Files.createDirectories(tmp.resolve("my-jdk/bin")).getParent();
        // the PATH entry as an older hook wrote it, with a doubled separator
        String written = mine + File.separator + File.separator + "bin";
        Map<String, String> env = new HashMap<>(Map.of("PATH", path(written, "/usr/bin"),
                "JAVA_HOME", mine + File.separator));
        apply(env, ShellEnv.use(env, home, Tool.JDK, jdk17, false));
        Map<String, String> reset = ShellEnv.reset(env, home, Tool.JDK);
        assertThat(reset.get("PATH")).isEqualTo(path(written, "/usr/bin"));
    }

    @Test
    void backToTheGlobalLinkPutsTheGlobalBinBack(@TempDir Path tmp) throws Exception {
        JkitHome own = new JkitHome(tmp.resolve(".jkit"));
        Files.createDirectories(own.global(Tool.JDK).getParent());
        Files.createSymbolicLink(own.global(Tool.JDK), Files.createDirectories(tmp.resolve("jdk")));
        Path jdk = Files.createDirectories(own.toolsRoot().resolve("jdk/21"));
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        apply(env, ShellEnv.use(env, own, Tool.JDK, jdk, false));
        Map<String, String> reset = ShellEnv.reset(env, own, Tool.JDK);
        assertThat(reset).containsEntry("JAVA_HOME", own.global(Tool.JDK).toString());
        assertThat(reset.get("PATH")).isEqualTo(path(own.global(Tool.JDK).resolve("bin").toString(), "/usr/bin"));
    }

    @Test
    void theGlobalLinkTheHookSetsIsNotTheUsersOwn() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "JAVA_HOME", p("/h/.jkit/global/jdk")));
        assertThat(ShellEnv.use(env, home, Tool.JDK, jdk17, false)).doesNotContainKey("JKIT_USER_JDK");
    }

    @Test
    void aGraalVmHomeSetOutsideJkitComesBackWhenTheJdkStopsBeingGraalVm() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "GRAALVM_HOME", "/opt/graal"));
        Map<String, String> use = ShellEnv.use(env, home, Tool.JDK, jdk21, true);
        assertThat(use).containsEntry("GRAALVM_HOME", jdk21.toString()).containsEntry("JKIT_USER_GRAALVM", "/opt/graal");
        apply(env, use);
        Map<String, String> other = ShellEnv.use(env, home, Tool.JDK, jdk17, false);
        assertThat(other).containsEntry("GRAALVM_HOME", "/opt/graal").containsEntry("JKIT_USER_GRAALVM", null);
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
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("JAVA_HOME", "/it's here");
        changes.put("OLD", null);
        assertThat(ShellEnv.format(changes, "bash")).isEqualTo("export JAVA_HOME='/it'\\''s here'\nunset OLD\n");
        assertThat(ShellEnv.format(changes, "fish")).startsWith("set -gx JAVA_HOME ");
        assertThat(ShellEnv.format(changes, "pwsh")).startsWith("$env:JAVA_HOME = ");
    }

    @Test
    void freshPutsChosenBinsFirstAndDropsOldJkitEntries() {
        Path maven = Path.of("/h/.jkit/global/maven");
        Map<String, String> env = Map.of("PATH", path(p("/h/.jkit/global/maven/bin"), bin(jdk17), "/usr/bin"));
        Map<String, String> vars = ShellEnv.fresh(env, home, Map.of(Tool.JDK, jdk21, Tool.MAVEN, maven), false);
        assertThat(vars.keySet()).containsExactly("JAVA_HOME", "MAVEN_HOME", "PATH");
        assertThat(vars.get("JAVA_HOME")).isEqualTo(jdk21.toString());
        assertThat(vars.get("PATH")).isEqualTo(path(bin(jdk21), p("/h/.jkit/global/maven/bin"), "/usr/bin"));
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
        Map<String, String> env = new HashMap<>(Map.of("PATH", path(p("/h/.jkit/global/jdk/bin"), "/usr/bin")));
        env.put(ShellEnv.stateVariable(Tool.MAVEN), p("/h/.jkit/tools/maven/3.9.11"));
        Path maven4 = Path.of("/h/.jkit/tools/maven/4.0.0");
        Map<String, String> out = ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21, Tool.MAVEN, maven4), false);
        assertThat(out).containsEntry("JKIT_PROJECT_JDK", jdk21.toString())
                .containsEntry("JAVA_HOME", jdk21.toString())
                .containsEntry("JKIT_PROJECT_MAVEN", maven4.toString())
                .doesNotContainKey("MAVEN_HOME"); // jkit use wins
        assertThat(out.get("PATH")).isEqualTo(path(bin(jdk21), p("/h/.jkit/tools/maven/3.9.11/bin"),
                p("/h/.jkit/global/jdk/bin"), "/usr/bin"));
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
        assertThat(env.get("PATH")).isEqualTo(path(bin(jdk17), "/usr/bin"));

        Map<String, String> out = ShellEnv.reset(env, home, Tool.JDK);
        assertThat(out).containsEntry("JAVA_HOME", jdk21.toString()).containsEntry("PATH", path(bin(jdk21), "/usr/bin"));
    }

    @Test
    void anotherToolsUseKeepsTheProjectOnPath() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        env.putAll(ShellEnv.project(env, home, Map.of(Tool.JDK, jdk21), false));
        Path maven = Path.of("/h/.jkit/tools/maven/3.9.11");
        assertThat(ShellEnv.use(env, home, Tool.MAVEN, maven, false).get("PATH"))
                .isEqualTo(path(bin(jdk21), bin(maven), "/usr/bin"));
    }
}
