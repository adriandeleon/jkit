package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** jkit prune: superseded builds, interrupted-install leftovers and old cached version lists. */
class PruneTest {
    @TempDir
    Path tmp;
    Path home;
    Path project;
    Map<String, String> env;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final Instant now = Instant.parse("2026-11-23T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve(".jkit");
        project = Files.createDirectories(tmp.resolve("src"));
        env = new HashMap<>(Map.of("HOME", tmp.toString(), "JKIT_HOME", home.toString(), "JKIT_PROJECT_FILE", ""));
        Files.createDirectories(home);
    }

    private Path install(Tool tool, String version, String vendor) throws Exception {
        Path dir = home.resolve("tools").resolve(tool.storeDir()).resolve(vendor.isEmpty() ? version : version + "-" + vendor);
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("jkit-install.properties"), "version=" + version + "\nvendor=" + vendor + "\n");
        Files.write(dir.resolve("bin/big"), new byte[4096]);
        return dir;
    }

    private int prune(boolean dryRun) throws Exception {
        out.reset();
        PruneCommand cmd = new PruneCommand();
        cmd.dryRun = dryRun;
        return cmd.run(Context.forEnv(env, new PrintStream(out, true, StandardCharsets.UTF_8)), project, now);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void removesOlderBuildsInALineAndKeepsTheNewest() throws Exception {
        Path old = install(Tool.JDK, "21.0.5+11", "temurin");
        Path newest = install(Tool.JDK, "21.0.9+10", "temurin");
        Path otherVendor = install(Tool.JDK, "21.0.4+7", "zulu");
        Path otherMajor = install(Tool.JDK, "17.0.12+7", "temurin");
        Path ea = install(Tool.JDK, "26-ea+3", "temurin");
        Path oldMaven = install(Tool.MAVEN, "3.9.9", "");
        Path maven = install(Tool.MAVEN, "3.9.11", "");

        prune(true);
        assertThat(output()).contains("would remove jdk 21.0.5+11 temurin").contains("21.0.9+10 is installed")
                .contains("would remove maven 3.9.9").contains("would free");
        assertThat(old).exists(); // a dry run changes nothing

        prune(false);
        assertThat(old).doesNotExist();
        assertThat(oldMaven).doesNotExist();
        assertThat(newest).exists();
        assertThat(otherVendor).exists();
        assertThat(otherMajor).exists();
        assertThat(ea).exists();
        assertThat(maven).exists();
        assertThat(output()).contains("removed jdk 21.0.5+11 temurin").contains("freed");
    }

    @Test
    void keepsTheGlobalBuildAndOneThisShellUses() throws Exception {
        Path global = install(Tool.JDK, "21.0.5+11", "temurin");
        install(Tool.JDK, "21.0.9+10", "temurin");
        Path shell = install(Tool.MAVEN, "3.9.9", "");
        install(Tool.MAVEN, "3.9.11", "");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), global);
        env.put(dev.jkit.core.ShellEnv.stateVariable(Tool.MAVEN), shell.toString());

        prune(false);
        assertThat(global).exists();
        assertThat(shell).exists();
        assertThat(output()).contains("nothing to prune");
    }

    @Test
    void keepsABuildTheProjectHerePinsExactly() throws Exception {
        Path pinned = install(Tool.JDK, "21.0.5+11", "temurin");
        install(Tool.JDK, "21.0.9+10", "temurin");
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = { version = \"21.0.5+11\", vendor = \"temurin\" }\n");
        env.remove("JKIT_PROJECT_FILE");

        prune(false);
        assertThat(pinned).exists();
    }

    @Test
    void deletesLeftoversAndOnlyOldCache() throws Exception {
        Path leftover = Files.createDirectories(home.resolve("tmp/install-123/out"));
        Path partial = Files.createDirectories(home.resolve("tools/jdk/.21.0.9+10-temurin.partial"));
        Path keepTmp = Files.createDirectories(home.resolve("tmp/other"));
        Path index = Files.createDirectories(home.resolve("index"));
        Path oldList = Files.writeString(index.resolve("aaa.cache"), "[]");
        Path recent = Files.writeString(index.resolve("bbb.cache"), "[]");
        Files.setLastModifiedTime(oldList, FileTime.from(now.minus(Duration.ofDays(31))));
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofDays(3))));

        prune(true);
        assertThat(output()).contains("install-123").contains("1 cached version list older than 30 days");
        prune(false);
        assertThat(leftover.getParent()).doesNotExist();
        assertThat(partial).doesNotExist();
        assertThat(oldList).doesNotExist();
        assertThat(recent).exists(); // still serves --offline
        assertThat(keepTmp).exists();
    }

    @Test
    void aBuildThatCantBeRemovedDoesntStopTheRest() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix") && !"root".equals(System.getProperty("user.name")));
        Path stuck = install(Tool.MAVEN, "3.9.9", "");
        install(Tool.MAVEN, "3.9.11", "");
        Path old = install(Tool.JDK, "21.0.5+11", "temurin");
        install(Tool.JDK, "21.0.9+10", "temurin");
        Path leftover = Files.createDirectories(home.resolve("tmp/install-999"));
        Path mavenDir = stuck.getParent();
        Files.setPosixFilePermissions(mavenDir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(prune(false)).isZero();
        } finally {
            Files.setPosixFilePermissions(mavenDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(stuck).exists();
        assertThat(old).doesNotExist();
        assertThat(leftover).doesNotExist();
        assertThat(output()).contains("warning: couldn't remove maven 3.9.9").contains("removed jdk 21.0.5+11 temurin");
    }

    @Test
    void aMissingHomeIsNothingToPruneAndIsNotCreated() throws Exception {
        Files.delete(home);
        prune(false);
        assertThat(output()).contains("nothing to prune");
        assertThat(home).doesNotExist();
    }
}
