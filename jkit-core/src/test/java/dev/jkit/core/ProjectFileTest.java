package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileTest {
    @TempDir
    Path tmp;
    Path home;
    Map<String, String> env;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        env = Map.of("HOME", home.toString());
    }

    private Path write(String relative, String content) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private Path dir(String relative) throws IOException {
        return Files.createDirectories(tmp.resolve(relative));
    }

    private ProjectSpec find(String relative) throws IOException {
        return ProjectFile.find(dir(relative), env).orElseThrow();
    }

    // jkit.toml

    @Test
    void readsShortAndLongTomlEntries() throws IOException {
        write("home/p/jkit.toml", """
                [tools]
                jdk = { version = "21.0.9+10", vendor = "zulu", fx = true }
                mvn = "3.9"
                gradle = "latest"
                graalvm = { version = "25", vendor = "graalvm-ce" }
                """);
        ProjectSpec spec = find("home/p");
        assertThat(spec.get(Tool.JDK)).isEqualTo(new PinnedTool(Tool.JDK, "21.0.9+10", Vendor.of("zulu"), true));
        assertThat(spec.get(Tool.MAVEN)).isEqualTo(new PinnedTool(Tool.MAVEN, "3.9", null, false));
        assertThat(spec.get(Tool.GRADLE).version()).isNull();
        assertThat(spec.get(Tool.GRAALVM).vendor()).isEqualTo(Vendor.of("graalvm-ce"));
        assertThat(spec.warnings()).isEmpty();
        assertThat(spec.source()).isEqualTo("jkit.toml");
    }

    @Test
    void skipsWhatItCannotUseWithWarningsThatNameTheLine() throws IOException {
        Path file = write("home/p/jkit.toml", """
                name = "payments"
                [tools]
                jdk = "21"
                kotlin = "2.1"
                maven = { version = "3.9", vendor = "apache", fx = true }
                gradle = "eight"
                graalvm = { version = "25", vendor = "temurin" }
                """);
        ProjectSpec spec = find("home/p");
        assertThat(spec.tools()).containsOnlyKeys(Tool.JDK, Tool.MAVEN);
        assertThat(spec.get(Tool.MAVEN)).isEqualTo(new PinnedTool(Tool.MAVEN, "3.9", null, false));
        assertThat(spec.warnings()).containsExactly(
                file + ":1: unknown key 'name', skipped",
                file + ":4: unknown tool 'kotlin', skipped (tools: jdk, maven, gradle, sbt, graalvm)",
                file + ":5: maven has no vendors; vendor 'apache' ignored",
                file + ":5: maven has no JavaFX builds; fx ignored",
                file + ":6: gradle = 'eight' is not a version, skipped",
                file + ":7: temurin is not GraalVM-based, skipped graalvm (graalvm vendors: graalvm-ce, graalvm, liberica-nik, mandrel)");
    }

    @Test
    void brokenTomlIsAnErrorWithTheLine() throws IOException {
        Path file = write("home/p/jkit.toml", "[tools]\njdk = \n");
        assertThatThrownBy(() -> find("home/p"))
                .isInstanceOf(JkitException.class)
                .hasMessageStartingWith(file + ":2: invalid TOML");
    }

    @Test
    void anAliasAndItsToolTogetherIsAnError() throws IOException {
        write("home/p/jkit.toml", "[tools]\njdk = \"21\"\njava = \"17\"\n");
        assertThatThrownBy(() -> find("home/p")).hasMessageContaining("both set jdk");
    }

    @Test
    void hiddenTomlWorksAndLosesToTheVisibleOne() throws IOException {
        write("home/a/.jkit.toml", "[tools]\njdk = \"17\"\n");
        assertThat(find("home/a").get(Tool.JDK).version()).isEqualTo("17");

        write("home/b/.jkit.toml", "[tools]\njdk = \"17\"\n");
        write("home/b/jkit.toml", "[tools]\njdk = \"21\"\n");
        ProjectSpec spec = find("home/b");
        assertThat(spec.get(Tool.JDK).version()).isEqualTo("21");
        assertThat(spec.warnings()).singleElement().asString().contains(".jkit.toml: ignored");
    }

    @Test
    void emptyTomlIsStillAProject() throws IOException {
        write("home/p/jkit.toml", "");
        write("home/p/.sdkmanrc", "java=21.0.5-tem\n");
        assertThat(find("home/p").tools()).isEmpty();
    }

    // other formats

    @Test
    void readsSdkmanrc() throws IOException {
        Path file = write("home/p/.sdkmanrc", """
                # Enable auto-env through the sdkman_auto_env config
                java=21.0.5-tem
                maven=3.9.9   # build
                kotlin=2.1.0
                scala=3.5.2
                gradle
                """);
        ProjectSpec spec = find("home/p");
        assertThat(spec.get(Tool.JDK)).isEqualTo(new PinnedTool(Tool.JDK, "21.0.5", Vendor.of("temurin"), false));
        assertThat(spec.get(Tool.MAVEN).version()).isEqualTo("3.9.9");
        assertThat(spec.warnings()).containsExactly(
                file + ":6: expected candidate=version, skipped",
                file + ": jkit doesn't manage kotlin, scala, skipped");
    }

    @Test
    void readsToolVersions() throws IOException {
        Path file = write("home/p/.tool-versions", """
                nodejs 22.11.0
                java temurin-21.0.5+11.0.LTS 17
                gradle 8.10
                maven system
                """);
        ProjectSpec spec = find("home/p");
        assertThat(spec.get(Tool.JDK)).isEqualTo(new PinnedTool(Tool.JDK, "21.0.5+11.0.LTS", Vendor.of("temurin"), false));
        assertThat(spec.get(Tool.GRADLE).version()).isEqualTo("8.10");
        assertThat(spec.tools()).doesNotContainKey(Tool.MAVEN);
        assertThat(spec.warnings()).containsExactly(
                file + ":4: 'system' is not a version jkit can use, skipped",
                file + ": jkit doesn't manage nodejs, skipped");
    }

    @Test
    void readsJavaVersion() throws IOException {
        write("home/a/.java-version", "\n17.0.2\n");
        assertThat(find("home/a").get(Tool.JDK)).isEqualTo(new PinnedTool(Tool.JDK, "17.0.2", null, false));

        write("home/b/.java-version", "1.8\n");
        assertThat(find("home/b").get(Tool.JDK).version()).isEqualTo("8");

        write("home/c/.java-version", "openjdk64-17.0.2\n");
        ProjectSpec jenv = find("home/c");
        assertThat(jenv.tools()).isEmpty();
        assertThat(jenv.warnings()).singleElement().asString().contains("is not a Java version");
    }

    @Test
    void mergesOtherFormatsInOneFolderEarlierOnesWinning() throws IOException {
        write("home/p/.java-version", "11\n");
        write("home/p/.tool-versions", "java 17\ngradle 8.10\n");
        write("home/p/.sdkmanrc", "java=21.0.5-tem\n");
        ProjectSpec spec = find("home/p");
        assertThat(spec.get(Tool.JDK).version()).isEqualTo("21.0.5");
        assertThat(spec.get(Tool.GRADLE).version()).isEqualTo("8.10");
        assertThat(spec.source()).isEqualTo(".sdkmanrc, .tool-versions, .java-version");
    }

    // discovery

    @Test
    void nearestFolderWinsAndFoldersAreNotMerged() throws IOException {
        write("home/repo/jkit.toml", "[tools]\njdk = \"21\"\nmaven = \"3.9\"\n");
        write("home/repo/legacy/.java-version", "8\n");
        ProjectSpec nested = find("home/repo/legacy/src/main");
        assertThat(nested.get(Tool.JDK).version()).isEqualTo("8");
        assertThat(nested.get(Tool.MAVEN)).isNull();
        assertThat(nested.dir()).isEqualTo(tmp.resolve("home/repo/legacy"));

        assertThat(find("home/repo/src").dir()).isEqualTo(tmp.resolve("home/repo"));
    }

    @Test
    void searchStopsAtHomeButChecksHomeItself() throws IOException {
        write("jkit.toml", "[tools]\njdk = \"8\"\n");      // above $HOME: never used from below it
        assertThat(ProjectFile.find(dir("home/p"), env)).isEmpty();

        write("home/jkit.toml", "[tools]\njdk = \"21\"\n");
        assertThat(find("home/p").get(Tool.JDK).version()).isEqualTo("21");
    }

    @Test
    void locateFindsTheSameFilesWithoutReadingThemAndKeyChangesWithTheFile() throws IOException {
        Path toml = write("home/repo/jkit.toml", "[tools\nbroken");
        write("home/legacy/.sdkmanrc", "java=17.0.13-zulu\n");
        Path jv = write("home/legacy/.java-version", "17\n");
        assertThat(ProjectFile.locate(dir("home/repo/src"), env)).containsExactly(toml);
        assertThat(ProjectFile.locate(dir("home/legacy"), env))
                .containsExactly(tmp.resolve("home/legacy/.sdkmanrc"), jv);
        assertThat(ProjectFile.locate(dir("home/none"), env)).isEmpty();

        String key = ProjectFile.key(java.util.List.of(toml));
        assertThat(key).startsWith(toml + ":");
        Files.setLastModifiedTime(toml, java.nio.file.attribute.FileTime.fromMillis(12345));
        assertThat(ProjectFile.key(java.util.List.of(toml))).isEqualTo(toml + ":12345").isNotEqualTo(key);
    }

    @Test
    void outsideHomeTheSearchGoesToTheRoot() throws IOException {
        write("ci/jkit.toml", "[tools]\njdk = \"21\"\n");
        assertThat(find("ci/workspace/repo").get(Tool.JDK).version()).isEqualTo("21");
    }

    @Test
    void overrideVariablePicksAFileOrTurnsProjectsOff() throws IOException {
        write("home/p/jkit.toml", "[tools]\njdk = \"21\"\n");
        Path other = write("elsewhere/.sdkmanrc", "java=17.0.13-zulu\n");

        Map<String, String> pointed = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE, other.toString());
        assertThat(ProjectFile.find(dir("home/p"), pointed).orElseThrow().get(Tool.JDK).version()).isEqualTo("17.0.13");

        Map<String, String> off = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE, "");
        assertThat(ProjectFile.find(dir("home/p"), off)).isEqualTo(Optional.empty());

        Map<String, String> missing = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE, "nope.toml");
        assertThatThrownBy(() -> ProjectFile.find(dir("home/p"), missing)).hasMessageContaining("is not a file");
    }
}
