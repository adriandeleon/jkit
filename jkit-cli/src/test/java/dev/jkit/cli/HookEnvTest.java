package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.core.ProjectFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** jkit hook-env, which the hook runs on cd with shell.auto_switch: what it changes and what it says. */
class HookEnvTest {
    @TempDir
    Path tmp;
    Path home;
    Path project;
    Path elsewhere;
    Map<String, String> env;
    Path temurin21;
    Path zulu17;
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("user/.jkit");
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        elsewhere = Files.createDirectories(tmp.resolve("user/src/other"));
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        zulu17 = install("jdk", "17.0.13+11-zulu", "version=17.0.13+11\nvendor=zulu\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), zulu17);
        env = new HashMap<>(Map.of("JKIT_HOME", home.toString(), "HOME", tmp.resolve("user").toString(),
                "JKIT_JDK_VENDOR", "temurin", "JKIT_SHELL", "bash",
                "JAVA_HOME", home.resolve("global/jdk").toString(),
                "PATH", home.resolve("global/jdk/bin") + File.pathSeparator + "/usr/bin"));
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    /** Runs hook-env in {@code dir} and applies its output to {@code env}, as the shell would. */
    private String cd(Path dir) throws Exception {
        err.reset();
        Context ctx = Context.forEnv(env, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String code = HookEnvCommand.run(ctx, dir, "bash", new PrintStream(err, true, StandardCharsets.UTF_8));
        for (String line : code.split("\n")) {
            if (line.startsWith("export ")) {
                String[] kv = line.substring("export ".length()).split("=", 2);
                env.put(kv[0], kv[1].substring(1, kv[1].length() - 1));
            } else if (line.startsWith("unset ")) {
                env.remove(line.substring("unset ".length()));
            }
        }
        return code;
    }

    private String said() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theFolderLeftIsFoundEvenWithSemicolonsInItsName() {
        String sep = File.separator;
        String file = sep + "src" + sep + "a;b" + sep + "jkit.toml"; // no ":" in a Windows name
        assertThat(HookEnvCommand.appliedDir(file + ":1700000000000;" + sep + "x" + sep + ".sdkmanrc:5"))
                .isEqualTo(Path.of(file).getParent().toString());
        assertThat(HookEnvCommand.appliedDir(file + ":1700000000000" + ProjectShell.MISSING))
                .isEqualTo(Path.of(file).getParent().toString());
    }

    @Test
    void outsideAProjectItDoesAndSaysNothing() throws Exception {
        assertThat(cd(elsewhere)).isEmpty();
        assertThat(said()).isEmpty();
    }

    @Test
    void enteringAProjectSwitchesAndSaysWhat() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        cd(project);
        assertThat(env).containsEntry("JAVA_HOME", temurin21.toString())
                .containsEntry("JKIT_PROJECT_JDK", temurin21.toString()).containsKey("JKIT_PROJECT");
        assertThat(env.get("PATH")).startsWith(temurin21.resolve("bin") + File.pathSeparator);
        assertThat(said()).contains("jkit: using ").contains("jkit.toml: jdk 21.0.9+10 temurin")
                .contains("set    JAVA_HOME=" + temurin21).contains("PATH   + " + temurin21.resolve("bin"));
    }

    /** JKIT_AUTO_SWITCH=0 exported in an open shell turns switching off there, from the next cd. */
    @Test
    void autoSwitchTurnedOffInThisShellChangesNothing() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        env.put("JKIT_AUTO_SWITCH", "0");
        assertThat(cd(project)).isEmpty();
        assertThat(said()).isEmpty();
        assertThat(env).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString());
        env.put("JKIT_AUTO_SWITCH", "1");
        cd(project);
        assertThat(env).containsEntry("JAVA_HOME", temurin21.toString());
    }

    /** The no-op answer comes from the environment and the files found, before a Context is built. */
    @Test
    void nothingToDoNeedsOnlyTheEnvironment() throws Exception {
        List<Path> none = List.of();
        assertThat(HookEnvCommand.nothingToDo(Map.of(), none)).isTrue();
        assertThat(HookEnvCommand.nothingToDo(Map.of("JKIT_PROJECT_JDK", temurin21.toString()), none)).isFalse(); // leaving
        Path file = Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        List<Path> files = List.of(file);
        assertThat(HookEnvCommand.nothingToDo(Map.of(), files)).isFalse(); // entering
        assertThat(HookEnvCommand.nothingToDo(Map.of("JKIT_PROJECT", ProjectFile.key(files)), files)).isTrue();
        assertThat(HookEnvCommand.nothingToDo(Map.of("JKIT_AUTO_SWITCH", "false"), files)).isTrue();
        assertThat(HookEnvCommand.nothingToDo(Map.of("JKIT_AUTO_SWITCH", "bogus"), files)).isFalse();
    }

    @Test
    void cdWithinTheSameProjectIsSilent() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        cd(project);
        assertThat(cd(Files.createDirectories(project.resolve("src/main")))).isEmpty();
        assertThat(said()).isEmpty();
    }

    @Test
    void anEditedProjectFileAppliesOnTheNextCd() throws Exception {
        Path file = project.resolve("jkit.toml");
        Files.writeString(file, "[tools]\njdk = \"21\"\n");
        cd(project);
        Files.writeString(file, "[tools]\njdk = \"17\"\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        cd(project);
        assertThat(env).containsEntry("JAVA_HOME", zulu17.toString());
        assertThat(said()).contains("jdk 17.0.13+11 zulu").contains("set    JAVA_HOME=" + zulu17);
    }

    @Test
    void leavingAProjectGoesBackToGlobalAndSaysSo() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        cd(project);
        cd(elsewhere);
        assertThat(env).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString())
                .doesNotContainKey("JKIT_PROJECT_JDK").doesNotContainKey("JKIT_PROJECT");
        assertThat(env.get("PATH")).doesNotContain(temurin21.toString());
        assertThat(said()).contains("jkit: left the project in").contains("back to the global versions")
                .contains("unset  JKIT_PROJECT_JDK").contains("PATH   - " + temurin21.resolve("bin"));
        assertThat(cd(elsewhere)).isEmpty();
    }

    @Test
    void aJavaHomeSetOutsideJkitComesBackOnLeavingAndThatIsSaid() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        env.put("JAVA_HOME", "/opt/my-jdk");
        cd(project);
        assertThat(env).containsEntry("JAVA_HOME", temurin21.toString()).containsEntry("JKIT_USER_JDK", "/opt/my-jdk");
        assertThat(said()).contains("set    JKIT_USER_JDK=/opt/my-jdk").contains("your own JAVA_HOME is kept in JKIT_USER_JDK");
        cd(elsewhere);
        assertThat(env).containsEntry("JAVA_HOME", "/opt/my-jdk").doesNotContainKey("JKIT_USER_JDK");
        assertThat(env.get("PATH")).doesNotContain(temurin21.toString());
        assertThat(said()).contains("left the project in").contains("jdk back to your own JAVA_HOME")
                .doesNotContain("back to the global").contains("set    JAVA_HOME=/opt/my-jdk")
                .contains("JAVA_HOME is your own again: /opt/my-jdk");
    }

    @Test
    void aMissingInstallIsNotedOnceAndPickedUpOnceInstalled() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"11\"\n");
        cd(project);
        assertThat(said()).contains("jkit.toml wants jdk 11, not installed: jkit sync");
        assertThat(env).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString());
        cd(project.resolve("."));
        assertThat(said()).isEmpty(); // looked again, nothing new: quiet
        cd(elsewhere); // leaving: nothing had changed, but jkit's note of the project goes, and that is said
        assertThat(said()).contains("left the project in").contains("it had changed no versions")
                .contains("unset  JKIT_PROJECT");
        assertThat(env).doesNotContainKey("JKIT_PROJECT");
        cd(project);
        Path jdk11 = install("jdk", "11.0.25+9-temurin", "version=11.0.25+9\nvendor=temurin\n");
        cd(project);
        assertThat(env).containsEntry("JAVA_HOME", jdk11.toString());
        assertThat(said()).contains("jdk 11.0.25+9 temurin").contains("set    JAVA_HOME=" + jdk11);
    }

    @Test
    void aShellChoiceKeepsWinningAndIsMentioned() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        env.put("JKIT_SHELL_JDK", zulu17.toString());
        env.put("JAVA_HOME", zulu17.toString());
        cd(project);
        assertThat(env).containsEntry("JAVA_HOME", zulu17.toString()).containsEntry("JKIT_PROJECT_JDK", temurin21.toString());
        assertThat(said()).contains("this shell chose its own jdk with jkit use, which wins over jkit.toml");
        cd(elsewhere); // leaving changes no version: the shell's own choice stays, and that is what it says
        assertThat(env).containsEntry("JAVA_HOME", zulu17.toString());
        assertThat(said()).contains("this shell's own jdk stays").doesNotContain("back to the global");
    }

    @Test
    void leavingNamesWhichToolsGoBackAndWhichStay() throws Exception {
        // the project pins jdk and maven; this shell chose its own jdk; PATH was reordered meanwhile
        install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\nmaven = \"3.9\"\n");
        env.put("JKIT_SHELL_JDK", zulu17.toString());
        env.put("JAVA_HOME", zulu17.toString());
        cd(project);
        env.put("PATH", "/venv/bin" + File.pathSeparator + env.get("PATH"));
        cd(elsewhere);
        assertThat(said()).contains("maven back to the global version; this shell's own jdk stays");
        assertThat(env).containsEntry("JAVA_HOME", zulu17.toString()).doesNotContainKey("MAVEN_HOME");
    }

    @Test
    void aBrokenProjectFileIsReportedOnceAndChangesNothing() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools\njdk = \"21\"\n");
        cd(project);
        assertThat(said()).startsWith("jkit: ").contains("jkit.toml").contains("this shell's versions are unchanged")
                .contains("set    JKIT_PROJECT=");
        assertThat(env).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString()).containsKey("JKIT_PROJECT");
        assertThat(cd(project)).isEmpty();
        assertThat(said()).isEmpty();
    }
}
