package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PinSyncTest {
    @TempDir
    Path tmp;
    Path home;
    Path project;
    Path envFile;
    Map<String, String> env;
    Path temurin21;
    Path zulu17;
    Path maven;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("user/.jkit");
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        zulu17 = install("jdk", "17.0.13+11-zulu", "version=17.0.13+11\nvendor=zulu\n");
        maven = install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/maven"), maven);
        envFile = tmp.resolve("env.sh");
        env = new HashMap<>(Map.of("JKIT_HOME", home.toString(), "HOME", tmp.resolve("user").toString(),
                "JKIT_JDK_VENDOR", "temurin", "PATH", "/usr/bin"));
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    private Context ctx() {
        return Context.forEnv(env, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private int pin(String spec, String vendor, boolean exact, boolean here) throws Exception {
        PinCommand pin = new PinCommand();
        pin.spec = ToolSpec.parse(spec);
        pin.vendorText = vendor;
        pin.exact = exact;
        pin.here = here;
        return pin.run(ctx(), project);
    }

    private String toml() throws Exception {
        return Files.readString(project.resolve("jkit.toml"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void pinNeverWritesThroughALinkedProjectFile() throws Exception {
        // a cloned repo's jkit.toml pointing at the user's own files, existing or not yet
        Path victim = tmp.resolve("user/victim.toml");
        Files.writeString(victim, "# mine\n");
        Files.createSymbolicLink(project.resolve("jkit.toml"), victim);
        assertThatThrownBy(() -> pin("jdk@21", null, false, true))
                .isInstanceOf(JkitException.class).hasMessageContaining("symbolic link");
        assertThat(Files.readString(victim)).isEqualTo("# mine\n");
        Files.delete(project.resolve("jkit.toml"));
        Files.createSymbolicLink(project.resolve("jkit.toml"), tmp.resolve("user/.bash_profile"));
        assertThatThrownBy(() -> pin("jdk@21", null, false, true))
                .hasMessageContaining("symbolic link");
        assertThat(tmp.resolve("user/.bash_profile")).doesNotExist();
    }

    @Test
    void pinCreatesJkitTomlWithTheVersionAsTyped() throws Exception {
        assertThat(pin("jdk@21", null, false, false)).isZero();
        assertThat(toml()).endsWith("[tools]\njdk = \"21\"\n");
        assertThat(output()).contains("pinned jdk 21 in").contains("jkit.toml created")
                .contains("the jkit shell hook isn't active");
    }

    @Test
    void exactWritesTheResolvedBuildAndVendor() throws Exception {
        pin("jdk@21", null, true, false);
        assertThat(toml()).contains("jdk = { version = \"21.0.9+10\", vendor = \"temurin\" }");
    }

    @Test
    void latestPinsTheNewestNotWhatThisShellUses() throws Exception {
        env.put("JAVA_HOME", zulu17.toString());
        PinCommand pin = new PinCommand();
        pin.spec = ToolSpec.parse("jdk@latest");
        pin.latest = true;
        assertThat(pin.run(ctx(), project)).isZero();
        assertThat(toml()).contains("jdk = \"latest\"");
    }

    @Test
    void aRelativeOverrideThatDoesNotExistYetIsTheFileCreated() throws Exception {
        Files.createDirectories(project.resolve("ci"));
        env.put("JKIT_PROJECT_FILE", "ci/jkit.toml");
        assertThat(pin("jdk@21", null, false, false)).isZero();
        assertThat(project.resolve("jkit.toml")).doesNotExist();
        assertThat(Files.readString(project.resolve("ci/jkit.toml"))).contains("jdk = \"21\"");
        env.put("JKIT_PROJECT_FILE", "nowhere/jkit.toml");
        assertThatThrownBy(() -> pin("jdk@21", null, false, false)).hasMessageContaining("its folder doesn't exist");
        // an absolute one too
        Path shared = Files.createDirectories(project.getParent().resolve("shared")).resolve("jkit.toml");
        env.put("JKIT_PROJECT_FILE", shared.toString());
        assertThat(pin("jdk@21", null, false, false)).isZero();
        assertThat(Files.readString(shared)).contains("jdk = \"21\"");
    }

    @Test
    void withoutAVersionPinsWhatThisShellUses() throws Exception {
        env.put("JAVA_HOME", zulu17.toString());
        pin("jdk", null, false, false);
        pin("maven", null, false, false); // from the global link
        assertThat(toml()).contains("jdk = \"17\"").contains("maven = \"3.9.11\"");
    }

    @Test
    void pinUpdatesTheNearestJkitTomlAndKeepsTheRest() throws Exception {
        Path root = project.getParent();
        Files.writeString(root.resolve("jkit.toml"), "# team\n[tools]\njdk = \"17\" # for now\nmaven = \"3.9\"\n");
        pin("jdk@21", null, false, false);
        assertThat(Files.readString(root.resolve("jkit.toml")))
                .isEqualTo("# team\n[tools]\njdk = \"21\" # for now\nmaven = \"3.9\"\n");
        assertThat(output()).contains("(was jdk 17)");
        assertThat(project.resolve("jkit.toml")).doesNotExist();
    }

    @Test
    void pinAndUnpinKeepTheFilesByteOrderMarkAndCharset() throws Exception {
        Path toml = project.resolve("jkit.toml");
        for (String charset : new String[] {"UTF-8", "UTF-16LE", "UTF-16BE"}) {
            Files.write(toml, ("﻿# café\r\n[tools]\r\nmaven = \"3.9\"\r\n").getBytes(charset));
            pin("jdk@21", null, false, false);
            assertThat(Files.readAllBytes(toml)).as(charset)
                    .isEqualTo(("﻿# café\r\n[tools]\r\nmaven = \"3.9\"\r\njdk = \"21\"\r\n").getBytes(charset));
            UnpinCommand unpin = new UnpinCommand();
            unpin.tool = Tool.JDK;
            unpin.run(ctx(), project);
            assertThat(Files.readAllBytes(toml)).as(charset)
                    .isEqualTo(("﻿# café\r\n[tools]\r\nmaven = \"3.9\"\r\n").getBytes(charset));
        }
        // not UTF-8: Latin-1, every byte kept
        byte[] latin1 = "# café\n[tools]\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(toml, latin1);
        pin("jdk@21", null, false, false);
        assertThat(Files.readAllBytes(toml)).startsWith(latin1);
    }

    @Test
    void pinRefusesFilesItOnlyReads() throws Exception {
        Files.writeString(project.resolve(".sdkmanrc"), "java=17.0.13-zulu\n");
        assertThatThrownBy(() -> pin("jdk@21", null, false, false))
                .isInstanceOf(JkitException.class).hasMessageContaining(".sdkmanrc, which jkit reads but doesn't write")
                .satisfies(e -> assertThat(((JkitException) e).hints().getFirst()).contains("jkit pin jdk@21 --here"));
        pin("jdk@21", null, false, true);
        assertThat(toml()).contains("jdk = \"21\"");
    }

    @Test
    void pinAppliesToThisShellWithTheHook() throws Exception {
        env.put("JKIT_ENV_FILE", envFile.toString());
        pin("jdk@21", null, false, false);
        assertThat(Files.readString(envFile)).contains("export JKIT_PROJECT_JDK='" + temurin21 + "'")
                .contains("export JAVA_HOME='" + temurin21 + "'")
                .contains("export PATH='" + temurin21.resolve("bin") + File.pathSeparator + "/usr/bin'");
    }

    @Test
    void pinSuggestsAutoSwitchOnlyInShellsThatHaveIt() throws Exception {
        env.put("JKIT_ENV_FILE", envFile.toString());
        env.put("JKIT_SHELL", "bash");
        pin("jdk@21", null, false, false);
        assertThat(output()).contains("to switch to a project's versions on cd: add auto_switch = true");
        out.reset();
        env.put("JKIT_SHELL", "pwsh"); // PowerShell's hook can't switch on cd yet
        pin("jdk@21", null, false, false);
        assertThat(output()).contains("set    JAVA_HOME=").doesNotContain("auto_switch");
    }

    @Test
    void unpinRemovesTheLineAndTheShellsProjectState() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\nmaven = \"3.9\"\n");
        env.put("JKIT_ENV_FILE", envFile.toString());
        env.put("JKIT_PROJECT_JDK", temurin21.toString());
        env.put("JAVA_HOME", temurin21.toString());
        UnpinCommand unpin = new UnpinCommand();
        unpin.tool = Tool.JDK;
        assertThat(unpin.run(ctx(), project)).isZero();
        assertThat(toml()).isEqualTo("[tools]\nmaven = \"3.9\"\n");
        assertThat(Files.readString(envFile)).contains("unset JKIT_PROJECT_JDK").contains("unset JAVA_HOME")
                .contains("export MAVEN_HOME='" + maven + "'");
    }

    @Test
    void unpinRemovesALineJkitCouldNotUse() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"abc\"\nmaven = \"3.9\"\n");
        UnpinCommand unpin = new UnpinCommand();
        unpin.tool = Tool.JDK;
        assertThat(unpin.run(ctx(), project)).isZero();
        assertThat(toml()).isEqualTo("[tools]\nmaven = \"3.9\"\n");
        assertThat(output()).contains("unpinned jdk (a line jkit couldn't use)");
    }

    @Test
    void syncAppliesInstalledPinsAndKeepsAShellChoice() throws Exception {
        Files.writeString(project.resolve(".sdkmanrc"), "java=21.0.9-tem\nmaven=3.9\n");
        env.put("JKIT_ENV_FILE", envFile.toString());
        Path ownMaven = install("maven", "4.0.0", "version=4.0.0\nvendor=\n");
        env.put("JKIT_SHELL_MAVEN", ownMaven.toString());
        SyncCommand sync = new SyncCommand();
        assertThat(sync.run(ctx(), project)).isZero();
        assertThat(output()).contains("syncing").contains("(.sdkmanrc)")
                .contains("jdk 21.0.9 temurin: 21.0.9+10 temurin (installed)")
                .contains("this shell chose its own maven with jkit use");
        assertThat(Files.readString(envFile)).contains("export JAVA_HOME='" + temurin21 + "'")
                .contains("export JKIT_PROJECT_MAVEN='" + maven + "'").doesNotContain("MAVEN_HOME");
    }

    @Test
    void resetWithoutAJkitChoiceLeavesTheUsersJavaHomeAlone() throws Exception {
        env.put("JKIT_ENV_FILE", envFile.toString());
        env.put("JAVA_HOME", "/opt/my-jdk");
        ResetCommand reset = new ResetCommand();
        reset.tool = Tool.JDK;
        assertThat(reset.run(ctx())).isZero();
        assertThat(envFile).doesNotExist();
        assertThat(output()).contains("nothing to reset: this shell has no jdk from jkit use or a project")
                .contains("JAVA_HOME stays as you set it: /opt/my-jdk");

        // with a jkit use on top, the user's comes back
        env.put("JKIT_SHELL_JDK", temurin21.toString());
        env.put("JKIT_USER_JDK", "/opt/my-jdk");
        env.put("JAVA_HOME", temurin21.toString());
        assertThat(reset.run(ctx())).isZero();
        assertThat(Files.readString(envFile)).contains("export JAVA_HOME='/opt/my-jdk'").contains("unset JKIT_USER_JDK");
    }

    @Test
    void syncWithoutAProjectFileFails() {
        assertThatThrownBy(() -> new SyncCommand().run(ctx(), project))
                .isInstanceOf(JkitException.class).hasMessageStartingWith("no jkit.toml");
    }

    @Test
    void statusShowsGraalVmFollowingAProjectsGraalVmJdk() throws Exception {
        Path graal = install("jdk", "25.0.1+8-graalvm-ce", "version=25.0.1+8\nvendor=graalvm_community\n");
        env.put("JKIT_ENV_FILE", envFile.toString());
        pin("jdk@25", null, false, false);
        for (String line : Files.readString(envFile).split("\n")) {
            if (line.startsWith("export ")) {
                String[] kv = line.substring("export ".length()).split("=", 2);
                env.put(kv[0], kv[1].replace("'", ""));
            }
        }
        assertThat(env).containsEntry("GRAALVM_HOME", graal.toString());
        out.reset();
        Context ctx = ctx();
        StatusCommand.print(ctx, StatusCommand.read(ctx, project), ctx.out);
        assertThat(output()).contains("graalvm").contains("jkit.toml (follows its GraalVM jdk)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMarksWhatTheProjectHerePicks() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\nmaven = \"4\"\n");
        Context ctx = ctx();
        Map<String, Object> doc = ListCommand.json(ctx, project);
        Map<String, Object> p = (Map<String, Object>) doc.get("project");
        assertThat(p.get("dir")).isEqualTo(project);
        Map<String, Object> tools = (Map<String, Object>) p.get("tools");
        assertThat(tools).containsEntry("jdk", temurin21).containsEntry("maven", null);

        var here = StatusCommand.read(ctx, project);
        var t21 = ctx.store.list(Tool.JDK).stream().filter(i -> i.home().equals(temurin21)).findFirst().orElseThrow();
        var z17 = ctx.store.list(Tool.JDK).stream().filter(i -> i.home().equals(zulu17)).findFirst().orElseThrow();
        assertThat(ListCommand.projectNotes(here, t21)).containsExactly("jkit.toml");
        assertThat(ListCommand.projectNotes(here, z17)).isEmpty();
        assertThat(ListCommand.json(ctx, tmp).get("project")).isNull();
    }
}
