package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.WorkDirs;
import dev.jkit.model.Tool;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

    /**
     * A build a running program uses stays, and prune says which program: here a process whose
     * arguments name a jar in an old Gradle (as a Gradle daemon's classpath does).
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBuildARunningProgramUsesIsKept() throws Exception {
        Path old = install(Tool.GRADLE, "8.14.2", "");
        install(Tool.GRADLE, "8.14.3", "");
        Path oldMaven = install(Tool.MAVEN, "3.9.9", "");
        install(Tool.MAVEN, "3.9.11", "");
        // `read` is built into sh, so no child (a forked `sleep` still carries the jar in its
        // arguments until it execs) outlives the shell when the test stops it; it waits on the
        // stdin pipe this test holds open
        Process daemon = new ProcessBuilder("/bin/sh", "-c", "read x", old.resolve("lib/gradle-daemon.jar").toString())
                .start();
        try {
            prune(true);
            assertThat(output()).contains("would keep gradle 8.14.2: a running program uses it (pid " + daemon.pid())
                    .contains("would remove maven 3.9.9").doesNotContain("would remove gradle");
            prune(false);
            assertThat(old).exists();
            assertThat(oldMaven).doesNotExist();
            assertThat(output()).contains("kept gradle 8.14.2: a running program uses it");
        } finally {
            daemon.destroyForcibly().waitFor();
        }
        prune(false);
        assertThat(old).doesNotExist(); // once it has stopped
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aBuildWhoseExecutableIsRunningIsKept() throws Exception {
        Path old = install(Tool.JDK, "21.0.5+11", "temurin");
        install(Tool.JDK, "21.0.9+10", "temurin");
        Path sleep = Path.of(Files.isExecutable(Path.of("/usr/bin/sleep")) ? "/usr/bin/sleep" : "/bin/sleep");
        Path java = Files.copy(sleep, old.resolve("bin/java"));
        Process running = new ProcessBuilder(java.toString(), "30").start();
        try {
            PruneCommand cmd = new PruneCommand();
            cmd.json.json = true;
            out.reset();
            cmd.run(Context.forEnv(env, new PrintStream(out, true, StandardCharsets.UTF_8)), project, now);
            assertThat(old).exists();
            JsonNode doc = new ObjectMapper().readTree(output());
            assertThat(doc.path("builds")).isEmpty();
            assertThat(doc.path("inUse").path(0).path("pid").asLong()).isEqualTo(running.pid());
            assertThat(doc.path("inUse").path(0).path("command").asText()).isEqualTo(java.toRealPath().toString());
        } finally {
            running.destroyForcibly().waitFor();
        }
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
        env.put(ShellEnv.stateVariable(Tool.MAVEN), shell.toString());

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
    void theFolderARunningInstallDownloadsIntoIsNoLeftover() throws Exception {
        // installs download outside the install lock, in a folder they hold
        Path running = WorkDirs.create(Files.createDirectories(home.resolve("tmp")), "install-");
        try {
            prune(true);
            assertThat(output()).doesNotContain("install-").contains("nothing to prune");
            prune(false);
            assertThat(running).isDirectory();
        } finally {
            WorkDirs.remove(running);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aWorkFolderJkitCantTellIsInUseIsNamedWithItsAgeAndKept() throws Exception {
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        // its lock file can't be locked (as on a file system without locks): maybe a running install
        Path tmpDir = Files.createDirectories(home.resolve("tmp"));
        Path old = Files.createDirectories(tmpDir.resolve("install-1"));
        Path young = Files.createDirectories(tmpDir.resolve("install-2"));
        for (Path p : java.util.List.of(old, young)) {
            Files.setPosixFilePermissions(Files.createFile(p.resolve(".lock")), PosixFilePermissions.fromString("r--r--r--"));
        }
        Files.setLastModifiedTime(old, FileTime.from(now.minus(Duration.ofDays(3))));
        Files.setLastModifiedTime(young, FileTime.from(now.minus(Duration.ofMinutes(20))));
        assertThat(WorkDirs.use(old)).isEqualTo(WorkDirs.Use.UNKNOWN);
        assertThat(WorkDirs.inUse(old)).isTrue();
        prune(false);
        assertThat(output())
                .contains("left " + old + " (3 days old): jkit can't tell whether an install is using it (this file"
                        + " system keeps no locks); probably left over: when no jkit is running, rm -rf " + old)
                .contains("left " + young + " (20 min old): jkit can't tell whether an install is using it (this file"
                        + " system keeps no locks)\n")
                .contains("nothing to prune");
        assertThat(old).isDirectory();
        assertThat(young).isDirectory();
        assertThat(PruneCommand.ago(Duration.ofHours(5))).isEqualTo("5 h");
        assertThat(PruneCommand.ago(Duration.ofDays(1))).isEqualTo("1 day");
    }

    @Test
    void aBuildThatCantBeRemovedDoesntStopTheRest() throws Exception {
        assumeTrue(FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix") && !"root".equals(System.getProperty("user.name")));
        Path stuck = install(Tool.MAVEN, "3.9.9", "");
        install(Tool.MAVEN, "3.9.11", "");
        Path old = install(Tool.JDK, "21.0.5+11", "temurin");
        install(Tool.JDK, "21.0.9+10", "temurin");
        Path leftover = Files.createDirectories(home.resolve("tmp/install-999"));
        Path mavenDir = stuck.getParent();
        Files.setPosixFilePermissions(mavenDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(prune(false)).isZero();
        } finally {
            Files.setPosixFilePermissions(mavenDir, PosixFilePermissions.fromString("rwxr-xr-x"));
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
