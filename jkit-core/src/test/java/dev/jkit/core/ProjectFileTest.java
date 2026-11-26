package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileTest {
    @TempDir
    Path tmp;
    Path home;
    Map<String, String> env;

    @Test
    void textALatin1FileCantHoldIsRefusedAndTheFileStays() throws Exception {
        byte[] latin1 = "# café\n[tools]\n".getBytes(StandardCharsets.ISO_8859_1);
        Path toml = Files.write(tmp.resolve("jkit.toml"), latin1);
        assertThat(TextFiles.encoding(toml).charset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> TextFiles.write(toml, TextFiles.text(toml) + "# ☕\n", false))
                .isInstanceOf(JkitException.class)
                .hasMessageEndingWith("is in ISO-8859-1, which can't hold the new text; nothing changed");
        assertThat(Files.readAllBytes(toml)).isEqualTo(latin1);
    }

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
    void aWholeNumberIsAVersionButADecimalIsRefused() throws IOException {
        Path file = write("home/p/jkit.toml", """
                [tools]
                jdk = 21
                maven = 3.10
                gradle = { version = 8 }
                sbt = { version = 1.10 }
                """);
        ProjectSpec spec = find("home/p");
        assertThat(spec.get(Tool.JDK).version()).isEqualTo("21");
        assertThat(spec.get(Tool.GRADLE).version()).isEqualTo("8");
        assertThat(spec.tools()).doesNotContainKeys(Tool.MAVEN, Tool.SBT);
        assertThat(spec.skipped()).containsExactlyInAnyOrder(Tool.MAVEN, Tool.SBT);
        assertThat(spec.warnings()).containsExactly(
                file + ":3: maven = 3.10 is a TOML number, which drops trailing zeros (3.10 reads as 3.1); "
                        + "quote it: maven = \"3.10\", skipped maven",
                file + ":5: sbt.version = 1.10 is a TOML number, which drops trailing zeros (3.10 reads as 3.1); "
                        + "quote it: version = \"1.10\", skipped sbt");
    }

    @Test
    void aPinItCannotReadIsSkippedButAnUnknownToolIsNot() throws IOException {
        write("home/p/jkit.toml", "[tools]\nkotlin = \"2.1\"\ngradle = \"eight\"\njdk = true\n");
        assertThat(find("home/p").skipped()).containsExactlyInAnyOrder(Tool.GRADLE, Tool.JDK);
        write("home/q/.tool-versions", "java system\nnodejs 20\n");
        assertThat(find("home/q").skipped()).containsExactly(Tool.JDK);
        write("home/r/.java-version", "system\n");
        assertThat(find("home/r").skipped()).containsExactly(Tool.JDK);
    }

    @Test
    void eachPinNamesTheFileThatSetIt() throws IOException {
        write("home/p/.sdkmanrc", "java=21.0.5-tem\n");
        write("home/p/.tool-versions", "java temurin-17\nmaven 3.9.9\n");
        ProjectSpec spec = find("home/p");
        assertThat(spec.source()).isEqualTo(".sdkmanrc, .tool-versions");
        assertThat(spec.source(Tool.JDK)).isEqualTo(".sdkmanrc");
        assertThat(spec.source(Tool.MAVEN)).isEqualTo(".tool-versions");
    }

    @Test
    void toolVersionsNotesWhereThePinDiffersFromTheFile() throws IOException {
        Path file = write("home/p/.tool-versions", "java temurin-jre-21.0.5+11.0.LTS\n");
        ProjectSpec spec = find("home/p");
        assertThat(spec.get(Tool.JDK)).isEqualTo(new PinnedTool(Tool.JDK, "21.0.5+11.0.LTS", Vendor.of("temurin"), false));
        assertThat(spec.warnings()).containsExactly(file + ":1: 'temurin-jre-21.0.5+11.0.LTS' is a JRE; "
                + "jkit installs full JDKs, pinned as the temurin JDK");
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

        String key = ProjectFile.key(List.of(toml));
        assertThat(key).startsWith(toml + ":");
        Files.setLastModifiedTime(toml, FileTime.fromMillis(12345));
        assertThat(ProjectFile.key(List.of(toml))).isEqualTo(toml + ":12345").isNotEqualTo(key);
    }

    @Test
    void outsideHomeTheSearchGoesToTheRoot() throws IOException {
        write("ci/jkit.toml", "[tools]\njdk = \"21\"\n");
        assertThat(find("ci/workspace/repo").get(Tool.JDK).version()).isEqualTo("21");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFolderOthersCanWriteStopsTheSearch() throws IOException {
        // like /tmp: another user's jkit.toml there must not apply to folders below it
        write("shared/jkit.toml", "[tools]\njdk = \"21\"\n");
        dir("shared/mine/repo");
        Files.setPosixFilePermissions(tmp.resolve("shared"), PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThat(ProjectFile.find(tmp.resolve("shared/mine/repo"), env)).isEqualTo(Optional.empty());
        assertThat(ProjectFile.trustProblem(tmp.resolve("shared/mine"))).isNull();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anIgnoredProjectFileIsReportedWithTheReason() throws IOException {
        write("shared/jkit.toml", "[tools]\njdk = \"21\"\n");
        dir("shared/repo");
        Files.setPosixFilePermissions(tmp.resolve("shared"), PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThat(ProjectFile.skipped(tmp.resolve("shared/repo"), env).orElseThrow())
                .contains("shared/jkit.toml").contains("every user can write to the folder");
        write("home/ok/jkit.toml", "[tools]\njdk = \"21\"\n");
        assertThat(ProjectFile.skipped(tmp.resolve("home/ok"), env)).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProjectFileEveryoneCanWriteIsIgnored() throws IOException {
        write("home/p/jkit.toml", "[tools]\njdk = \"21\"\n");
        Files.setPosixFilePermissions(tmp.resolve("home/p/jkit.toml"),
                PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThat(ProjectFile.find(tmp.resolve("home/p"), env)).isEmpty();
        assertThat(ProjectFile.skipped(tmp.resolve("home/p"), env).orElseThrow()).contains("every user can write to it");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProjectFileLinkingOutOfItsFolderIsIgnored() throws IOException {
        // .java-version -> /proc/self/environ would print every variable in a warning
        Path secret = write("home/secret.txt", "TOKEN=ghp_x\n");
        dir("home/p");
        Files.createSymbolicLink(tmp.resolve("home/p/.java-version"), secret);
        assertThat(ProjectFile.find(tmp.resolve("home/p"), env)).isEmpty();
        assertThat(ProjectFile.skipped(tmp.resolve("home/p"), env).orElseThrow()).contains("link to a file outside");
        write("home/q/real.txt", "21\n");
        Files.createSymbolicLink(tmp.resolve("home/q/.java-version"), tmp.resolve("home/q/real.txt"));
        assertThat(find("home/q").get(Tool.JDK).version()).isEqualTo("21");
    }

    @Test
    void warningsQuoteAtMostAShortExcerpt() throws IOException {
        write("home/w/.java-version", "x".repeat(500) + "\n");
        String warning = find("home/w").warnings().getFirst();
        // the file's content is cut to 40 characters (the path in front varies by OS)
        assertThat(warning).contains("'" + "x".repeat(40) + "…'").doesNotContain("x".repeat(41));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void everyFileReadTogetherIsChecked() throws IOException {
        // .sdkmanrc, .tool-versions and .java-version are read together: a bad second one counts too
        write("home/p/.sdkmanrc", "java=21.0.2-tem\n");
        write("home/p/.tool-versions", "maven 3.9.9\n");
        Files.setPosixFilePermissions(tmp.resolve("home/p/.tool-versions"),
                PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThat(ProjectFile.find(tmp.resolve("home/p"), env)).isEmpty();
        assertThat(ProjectFile.skipped(tmp.resolve("home/p"), env).orElseThrow()).contains(".tool-versions");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aRelativeOverrideFollowsTheSameRules() throws IOException {
        write("shared/x.toml", "[tools]\njdk = \"21\"\n");
        Files.setPosixFilePermissions(tmp.resolve("shared"), PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(() -> ProjectFile.find(tmp.resolve("shared"), Map.of(ProjectFile.OVERRIDE, "x.toml")))
                .isInstanceOf(JkitException.class).hasMessageContaining("every user can write");
        // an absolute path is the user's own choice
        assertThat(ProjectFile.find(tmp.resolve("shared"),
                Map.of(ProjectFile.OVERRIDE, tmp.resolve("shared/x.toml").toString()))).isPresent();
    }

    @Test
    void aHugeProjectFileIsAnErrorNotAHang() throws IOException {
        write("home/big/jkit.toml", "# " + "x".repeat(300 * 1024) + "\n[tools]\njdk = \"21\"\n");
        assertThatThrownBy(() -> find("home/big")).isInstanceOf(JkitException.class).hasMessageContaining("too big");
    }

    @Test
    void deepNestingIsAnErrorNotACrash() throws IOException {
        write("home/deep/jkit.toml", "a = " + "[".repeat(200_000) + "\n");
        assertThatThrownBy(() -> find("home/deep")).isInstanceOf(JkitException.class).hasMessageContaining("invalid TOML");
    }

    @Test
    void pinsWithControlCharactersAreSkipped() throws IOException {
        write("home/p/jkit.toml", "[tools]\njdk = { version = \"21\", vendor = \"zulu\\u001b]0;x\\u0007\" }\n"
                + "maven = \"3.9\\u001b[2J\"\ngradle = \"8.14\"\n");
        ProjectSpec spec = find("home/p");
        assertThat(spec.tools()).containsOnlyKeys(Tool.GRADLE);
        assertThat(spec.warnings()).anyMatch(w -> w.contains("jdk pins a vendor with characters jkit doesn't accept"))
                .anyMatch(w -> w.contains("maven pins a version"));
        assertThat(String.join("", spec.warnings())).doesNotContain("\u001b");
    }

    @Test
    void overrideVariablePicksAFileOrTurnsProjectsOff() throws IOException {
        write("home/p/jkit.toml", "[tools]\njdk = \"21\"\n");
        Path other = write("elsewhere/.sdkmanrc", "java=17.0.13-zulu\n");

        Map<String, String> pointed = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE, other.toString());
        assertThat(ProjectFile.find(dir("home/p"), pointed).orElseThrow().get(Tool.JDK).version()).isEqualTo("17.0.13");

        Map<String, String> off = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE, "");
        assertThat(ProjectFile.find(dir("home/p"), off)).isEqualTo(Optional.empty());

        // a relative name applies only where it exists; an absolute one must exist
        Map<String, String> relative = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE, "nope.toml");
        assertThat(ProjectFile.find(dir("home/p"), relative)).isEmpty();
        Map<String, String> missing = Map.of("HOME", home.toString(), ProjectFile.OVERRIDE,
                tmp.resolve("nope.toml").toString());
        assertThatThrownBy(() -> ProjectFile.find(dir("home/p"), missing)).hasMessageContaining("is not a file");
    }
}
