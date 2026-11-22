package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.core.ToolState.Source;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolStatesTest {
    @TempDir
    Path tmp;
    JkitHome home;
    Store store;
    Path project;
    Map<String, String> env;
    Path temurin21;
    Path zulu21;
    Path zulu17;
    Path maven;

    private static final ToolStates.Links LINKS = link -> {
        if (!Files.isSymbolicLink(link)) {
            return null;
        }
        return link.getParent().resolve(Files.readSymbolicLink(link)).normalize();
    };

    @BeforeEach
    void setUp() throws IOException {
        home = new JkitHome(tmp.resolve("user/.jkit"));
        store = new Store(home);
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        env = new HashMap<>(Map.of("HOME", tmp.resolve("user").toString()));
        temurin21 = install("jdk", "21.0.9+10", "temurin", false);
        zulu21 = install("jdk", "21.0.10+7", "zulu", false);
        zulu17 = install("jdk", "17.0.13+11", "zulu", false);
        maven = install("maven", "3.9.11", null, false);
    }

    private Path install(String tool, String version, String vendor, boolean fx) throws IOException {
        String dir = version + (vendor == null ? "" : "-" + vendor) + (fx ? "-fx" : "");
        Path path = Files.createDirectories(home.toolsRoot().resolve(tool).resolve(dir));
        Files.writeString(path.resolve(Store.MARKER), "tool=" + tool + "\nversion=" + version + "\nvendor="
                + (vendor == null ? "" : vendor) + "\nfx=" + fx + "\n");
        return path;
    }

    private void global(Tool tool, Path target) throws IOException {
        Files.createDirectories(home.global(tool).getParent());
        Files.createSymbolicLink(home.global(tool), target);
    }

    private void pin(String toml) throws IOException {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\n" + toml + "\n");
    }

    private ToolStates.Result read() throws IOException {
        return read(new Config("temurin", "graalvm-ce"));
    }

    private ToolStates.Result read(Config config) throws IOException {
        return ToolStates.read(env, project, home, store, config, LINKS);
    }

    @Test
    void nothingAtAll() throws IOException {
        ToolState jdk = read().get(Tool.JDK);
        assertThat(jdk.source()).isEqualTo(Source.NONE);
        assertThat(jdk.wanted()).isEqualTo(Source.NONE);
        assertThat(jdk.isEmpty()).isTrue();
        assertThat(jdk.matches()).isTrue();
    }

    @Test
    void globalThroughTheLinkPath() throws IOException {
        global(Tool.JDK, zulu17);
        env.put("JAVA_HOME", home.global(Tool.JDK).toString());
        ToolState jdk = read().get(Tool.JDK);
        assertThat(jdk.wanted()).isEqualTo(Source.GLOBAL);
        assertThat(jdk.source()).isEqualTo(Source.GLOBAL);
        assertThat(jdk.active().home()).isEqualTo(zulu17);
        assertThat(jdk.matches()).isTrue();
    }

    @Test
    void shellChoiceBeatsProjectAndGlobal() throws IOException {
        global(Tool.JDK, zulu17);
        pin("jdk = \"21\"");
        env.put("JKIT_SHELL_JDK", zulu17.toString());
        env.put("JAVA_HOME", zulu17.toString());
        ToolState jdk = read().get(Tool.JDK);
        assertThat(jdk.wanted()).isEqualTo(Source.SHELL);
        assertThat(jdk.source()).isEqualTo(Source.SHELL);
        assertThat(jdk.pinInstall().home()).isEqualTo(temurin21);
    }

    @Test
    void projectPinWantedButNotAppliedYet() throws IOException {
        global(Tool.JDK, zulu17);
        pin("jdk = \"21\"");
        env.put("JAVA_HOME", home.global(Tool.JDK).toString());
        ToolState jdk = read().get(Tool.JDK);
        assertThat(jdk.wanted()).isEqualTo(Source.PROJECT);
        assertThat(jdk.wantedHome()).isEqualTo(temurin21);
        assertThat(jdk.source()).isEqualTo(Source.GLOBAL);
        assertThat(jdk.matches()).isFalse();
    }

    @Test
    void projectPinApplied() throws IOException {
        pin("jdk = \"21\"");
        env.put("JAVA_HOME", temurin21.toString());
        ToolState jdk = read().get(Tool.JDK);
        assertThat(jdk.source()).isEqualTo(Source.PROJECT);
        assertThat(jdk.matches()).isTrue();
    }

    @Test
    void pinWithoutVendorPrefersTheDefaultVendorThenNewest() throws IOException {
        pin("jdk = \"21\"");
        assertThat(read(new Config("temurin", "graalvm-ce")).get(Tool.JDK).pinInstall().home()).isEqualTo(temurin21);
        assertThat(read(new Config("corretto", "graalvm-ce")).get(Tool.JDK).pinInstall().home()).isEqualTo(zulu21);
    }

    @Test
    void pinPrefersPlainBuildsUnlessItAsksForFx() throws IOException {
        Path fx = install("jdk", "21.0.11+3", "zulu", true);
        assertThat(ToolStates.resolve(store, new Config("zulu", "graalvm-ce"),
                new PinnedTool(Tool.JDK, "21", null, false))).get().extracting(Installed::home).isEqualTo(zulu21);
        assertThat(ToolStates.resolve(store, new Config("zulu", "graalvm-ce"),
                new PinnedTool(Tool.JDK, "21", Vendor.of("zulu"), true))).get().extracting(Installed::home).isEqualTo(fx);
    }

    @Test
    void pinWithNothingInstalled() throws IOException {
        pin("maven = \"4.0\"");
        ToolState mvn = read().get(Tool.MAVEN);
        assertThat(mvn.wanted()).isEqualTo(Source.PROJECT);
        assertThat(mvn.pinInstall()).isNull();
        assertThat(mvn.wantedHome()).isNull();
        assertThat(mvn.matches()).isFalse();
    }

    @Test
    void outsideAndStaleHomes() throws IOException {
        Path system = Files.createDirectories(tmp.resolve("usr/lib/jvm/java-21"));
        Files.writeString(system.resolve("release"), "IMPLEMENTOR=\"Debian\"\nJAVA_VERSION=\"21.0.13\"\n");
        env.put("JAVA_HOME", system.toString());
        env.put("MAVEN_HOME", maven.toString());
        ToolStates.Result result = read();
        assertThat(result.get(Tool.JDK).source()).isEqualTo(Source.OUTSIDE);
        assertThat(result.get(Tool.JDK).active()).isNull();
        assertThat(ToolStates.releaseVersion(system)).isEqualTo("21.0.13");
        assertThat(result.get(Tool.MAVEN).source()).isEqualTo(Source.STALE);
        assertThat(result.get(Tool.MAVEN).active().home()).isEqualTo(maven);
    }

    @Test
    void globalLinkToARemovedInstall() throws IOException {
        global(Tool.MAVEN, home.toolsRoot().resolve("maven/3.9.6"));
        ToolState mvn = read().get(Tool.MAVEN);
        assertThat(mvn.globalMissing()).isTrue();
        assertThat(mvn.wanted()).isEqualTo(Source.NONE);
    }

    @Test
    void graalVmFollowsAGraalVmJdkChosenInTheShell() throws IOException {
        Path graal = install("jdk", "25.0.1+8", "graalvm_community", false);
        env.put("JKIT_SHELL_JDK", graal.toString());
        env.put("JKIT_GRAALVM_FOLLOWS_JDK", "1");
        env.put("GRAALVM_HOME", graal.toString());
        ToolState graalvm = read().get(Tool.GRAALVM);
        assertThat(graalvm.source()).isEqualTo(Source.SHELL);
        assertThat(graalvm.active().home()).isEqualTo(graal);
    }

    @Test
    void brokenProjectFileIsReportedNotThrown() throws IOException {
        Files.writeString(project.resolve("jkit.toml"), "[tools\n");
        ToolStates.Result result = read();
        assertThat(result.projectError()).contains("invalid TOML");
        assertThat(result.project()).isEmpty();
        assertThat(result.tools()).hasSize(4);
    }
}
