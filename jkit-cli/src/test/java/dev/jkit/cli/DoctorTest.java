package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jkit.cli.DoctorCommand.Finding;
import dev.jkit.cli.DoctorCommand.Level;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Doctor against crafted homes; fake java and jkit binaries are shell scripts, hence no Windows. */
@DisabledOnOs(OS.WINDOWS)
class DoctorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;
    Path home;
    Path project;
    Path usrBin;
    Map<String, String> env;
    Path temurin21;

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("user/.jkit");
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        usrBin = Files.createDirectories(tmp.resolve("usr/bin"));
        executable(usrBin.resolve("jkit"), "exit 0");
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        javaScript(temurin21, "echo 'openjdk version \"21.0.9\" 2025-10-21' >&2; exit 0");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), temurin21);
        // a healthy hooked shell: global jdk active and first on PATH
        env = new HashMap<>(Map.of(
                "JKIT_HOME", home.toString(),
                "HOME", tmp.resolve("user").toString(),
                "JKIT_SHELL", "bash",
                "JKIT_ENV_FILE", tmp.resolve("jkit-env").toString(), // run through the hook's jkit function
                "JAVA_HOME", home.resolve("global/jdk").toString(),
                "PATH", path(home.resolve("global/jdk/bin"), usrBin)));
    }

    private static String path(Path... entries) {
        return String.join(File.pathSeparator, Arrays.stream(entries).map(Path::toString).toList());
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    private static void executable(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static void javaScript(Path jdk, String body) throws Exception {
        executable(jdk.resolve("bin/java"), body);
    }

    private List<Finding> run() {
        return DoctorCommand.run(Context.lenient(env, System.out), project, false);
    }

    private static List<Finding> of(List<Finding> findings, String id) {
        return findings.stream().filter(f -> f.id().equals(id)).toList();
    }

    private static Finding only(List<Finding> findings, String id) {
        List<Finding> matching = of(findings, id);
        assertThat(matching).as(id).hasSize(1);
        return matching.getFirst();
    }

    @Test
    void doctorNeverCreatesTheJkitHome() {
        // "changes nothing": on a fresh machine it says the home doesn't exist, and leaves it so
        Path fresh = tmp.resolve("fresh/.jkit");
        env.put("JKIT_HOME", fresh.toString());
        DoctorCommand.run(Context.lenient(env, System.out), project, false);
        assertThat(fresh).doesNotExist();
        assertThat(fresh.getParent()).satisfiesAnyOf(p -> assertThat(p).doesNotExist(), p -> assertThat(p).isEmptyDirectory());
    }

    @Test
    void healthySetupHasNoProblems() {
        List<Finding> findings = run();
        assertThat(findings).noneMatch(f -> f.level() == Level.WARN || f.level() == Level.FAIL);
        assertThat(only(findings, "hook").message()).isEqualTo("shell hook loaded (bash)");
        assertThat(only(findings, "path-order").message()).isEqualTo("java on PATH comes from jkit's versions");
        assertThat(only(findings, "java-runs").message()).startsWith("active JDK runs: openjdk version \"21.0.9\"");
        assertThat(only(findings, "store").message()).isEqualTo("1 install, nothing left over");
        assertThat(of(findings, "network")).isEmpty();
    }

    @Test
    void temporaryFilesAnInterruptedWriteLeftAreReported() throws Exception {
        assertThat(of(run(), "temp-files")).isEmpty();
        Path rcLeft = Files.writeString(tmp.resolve("user/.bashrc.8121361834962231183.jkit-tmp"), "half");
        Path configLeft = Files.writeString(home.resolve(".config.toml.42.jkit-tmp"), "half");
        Path tomlLeft = Files.writeString(project.resolve(".jkit.toml.7.jkit-tmp"), "half");
        Files.writeString(tmp.resolve("user/notes.jkit-tmp"), "not jkit's naming");
        assertThat(of(run(), "temp-files")).extracting(Finding::message).containsExactly(
                "a temporary file an interrupted jkit write left: " + Changes.tilde(rcLeft.toString()),
                "a temporary file an interrupted jkit write left: " + Changes.tilde(configLeft.toString()),
                "a temporary file an interrupted jkit write left: " + Changes.tilde(tomlLeft.toString()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aTemporaryFileNextToALinkedStartupFileIsReported() throws Exception {
        Path dotfiles = Files.createDirectories(tmp.resolve("user/dotfiles"));
        Files.createSymbolicLink(tmp.resolve("user/.bashrc"), Files.writeString(dotfiles.resolve("bashrc"), ""));
        Path left = Files.writeString(dotfiles.resolve(".bashrc.81.jkit-tmp"), "half");
        assertThat(of(run(), "temp-files")).extracting(Finding::message)
                .containsExactly("a temporary file an interrupted jkit write left: " + Changes.tilde(left.toString()));
    }

    @Test
    void missingHookSaysWhatToAddForTheUsersShell() {
        env.remove("JKIT_SHELL");
        env.put("SHELL", "/usr/bin/zsh");
        Finding hook = only(run(), "hook");
        assertThat(hook.level()).isEqualTo(Level.WARN);
        assertThat(hook.fix()).isEqualTo("jkit hook zsh --install, then open a new shell");
    }

    @Test
    void inheritedHookWithoutTheFunctionIsAWarning() {
        env.remove("JKIT_ENV_FILE"); // JKIT_SHELL came from a parent shell; no jkit function here
        Finding hook = only(run(), "hook");
        assertThat(hook.level()).isEqualTo(Level.WARN);
        assertThat(hook.message()).contains("inherited");
    }

    @Test
    void anotherJavaEarlierOnPath() throws Exception {
        executable(usrBin.resolve("java"), "exit 0");
        env.put("PATH", path(usrBin, home.resolve("global/jdk/bin")));
        Finding order = only(run(), "path-order");
        assertThat(order.level()).isEqualTo(Level.WARN);
        assertThat(order.message()).isEqualTo("'java' on PATH is " + usrBin.resolve("java") + ", not $JAVA_HOME/bin/java");
    }

    @Test
    void javaHomeSetOutsideJkit() throws Exception {
        Path system = Files.createDirectories(tmp.resolve("usr/lib/jvm/java-17"));
        javaScript(system, "echo 'openjdk version \"17.0.2\"' >&2");
        env.put("JAVA_HOME", system.toString());
        List<Finding> findings = run();
        Finding outside = only(findings, "outside-vars");
        assertThat(outside.level()).isEqualTo(Level.WARN);
        assertThat(outside.message()).isEqualTo("JAVA_HOME=" + system + " is set outside jkit and wins over the global jdk");
        assertThat(only(findings, "java-runs").message()).contains("17.0.2");
    }

    @Test
    void brokenLinksFailWithAFix() throws Exception {
        install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createSymbolicLink(home.resolve("global/maven"), home.resolve("tools/maven/3.9.6"));
        env.put("JKIT_SHELL_JDK", home.resolve("tools/jdk/17.0.1-zulu").toString());
        List<Finding> links = of(run(), "links");
        assertThat(links).extracting(Finding::level).containsOnly(Level.FAIL);
        assertThat(links).extracting(Finding::fix).containsExactlyInAnyOrder(
                "jkit use maven@3.9.11 --global, or remove the link: rm " + home.resolve("global/maven"),
                "jkit reset jdk");
    }

    @Test
    void leftoversInTheStore() throws Exception {
        Path half = Files.createDirectories(home.resolve("tools/jdk/25.0.1+8-temurin"));
        Files.createDirectories(home.resolve("tmp"));
        Files.write(home.resolve("tmp/download.part"), new byte[3000]);
        List<Finding> store = of(run(), "store");
        assertThat(store).extracting(Finding::message).containsExactly(
                "unfinished install (no jkit-install.properties): " + half,
                "2 KB left in " + home.resolve("tmp") + " by interrupted downloads");
        // prune deletes what interrupted installs left and keeps a folder a running one uses; a
        // file in tmp/ that no install made is named for rm
        assertThat(store.get(1).fix()).startsWith("delete it: jkit prune (")
                .endsWith("; the rest: rm -rf " + home.resolve("tmp/download.part"));
        Files.delete(home.resolve("tmp/download.part"));
        Files.write(Files.createDirectories(home.resolve("tmp/install-1")).resolve("jdk.tar.gz"), new byte[3000]);
        assertThat(of(run(), "store").get(1).fix()).isEqualTo(
                "delete it: jkit prune (which also removes builds a newer one replaced; --dry-run shows what)");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aWorkFolderJkitCantTellIsInUseIsNamedWithItsAgeNotCountedAsLeftOver() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(!"root".equals(System.getProperty("user.name")));
        // its lock file can't be locked (as on a file system without locks): maybe a running install
        Path tmpDir = Files.createDirectories(home.resolve("tmp"));
        Path old = Files.createDirectories(tmpDir.resolve("install-1"));
        Path young = Files.createDirectories(tmpDir.resolve("install-2"));
        for (Path p : List.of(old, young)) {
            Files.setPosixFilePermissions(Files.createFile(p.resolve(".lock")), PosixFilePermissions.fromString("r--r--r--"));
        }
        java.time.Instant now = java.time.Instant.now();
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(now.minus(java.time.Duration.ofDays(3))));
        Files.setLastModifiedTime(young, java.nio.file.attribute.FileTime.from(now.minus(java.time.Duration.ofHours(2))));
        List<Finding> store = of(run(), "store");
        assertThat(store).extracting(Finding::level).containsExactly(Level.WARN, Level.INFO);
        assertThat(store.get(0).message()).isEqualTo(old + " (3 days old): jkit can't tell whether an install is using it"
                + " (this file system keeps no locks); probably left over");
        assertThat(store.get(0).fix()).isEqualTo("when no jkit is running: rm -rf " + old);
        assertThat(store.get(1).message()).startsWith(young + " (2 h old): jkit can't tell").doesNotContain("left over");
        assertThat(old).isDirectory();
    }

    @Test
    void brokenConfigIsOneFailAndTheOtherChecksStillRun() throws Exception {
        Files.writeString(home.resolve("config.toml"), "[jdk\nvendor = \"zulu\"\n");
        List<Finding> findings = run();
        Finding config = only(findings, "config");
        assertThat(config.level()).isEqualTo(Level.FAIL);
        assertThat(config.message()).contains("config.toml");
        assertThat(only(findings, "java-runs").level()).isEqualTo(Level.OK);
    }

    @Test
    void aJdkThatDoesNotRunFailsWithAReinstallFix() throws Exception {
        javaScript(temurin21, "echo 'Error: could not find libjava.so' >&2; exit 1");
        Finding runs = only(run(), "java-runs");
        assertThat(runs.level()).isEqualTo(Level.FAIL);
        assertThat(runs.message()).endsWith("java -version exited 1: Error: could not find libjava.so");
        assertThat(runs.fix()).isEqualTo("reinstall it: jkit remove jdk@21.0.9+10 temurin, then jkit install jdk@21.0.9+10 temurin");
    }

    @Test
    void twoJkitBinariesOnPath() throws Exception {
        Path local = tmp.resolve("usr/local/bin");
        executable(local.resolve("jkit"), "exit 0");
        env.put("PATH", path(home.resolve("global/jdk/bin"), local, usrBin));
        Finding binary = only(run(), "binary");
        assertThat(binary.level()).isEqualTo(Level.WARN);
        assertThat(binary.message()).startsWith("2 different jkit binaries on PATH, the first wins: " + local.resolve("jkit"));
    }

    @Test
    void projectProblems() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\nmaven = \"3.9\"\nkotlin = \"2\"\n");
        List<Finding> found = of(run(), "project");
        assertThat(found).extracting(Finding::level).containsOnly(Level.WARN);
        assertThat(found).extracting(Finding::fix).contains("jkit install maven@3.9");
        assertThat(found).anyMatch(f -> f.message().contains("unknown tool 'kotlin'"));

        Files.writeString(project.resolve("jkit.toml"), "[tools\n");
        Finding broken = only(run(), "project");
        assertThat(broken.level()).isEqualTo(Level.FAIL);
        assertThat(broken.message()).contains("invalid TOML");
    }

    @Test
    void textAndJsonSummaries() throws Exception {
        env.remove("JKIT_SHELL");
        Files.createSymbolicLink(home.resolve("global/maven"), home.resolve("tools/maven/3.9.6"));
        List<Finding> findings = run();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DoctorCommand.print(findings, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("warn  shell hook not loaded").contains("      fix: " + Changes.HOOK_SETUP)
                .endsWith("1 warning, 1 problem\n");

        JsonNode doc = JSON.readTree(JsonOut.write(DoctorCommand.json(findings)));
        assertThat(doc.path("summary").path("warn").asInt()).isEqualTo(1);
        assertThat(doc.path("summary").path("fail").asInt()).isEqualTo(1);
        JsonNode first = doc.path("checks").get(0);
        assertThat(first.path("id").asText()).isEqualTo("home");
        assertThat(first.path("level").asText()).isEqualTo("ok");
        assertThat(first.has("fix")).isTrue();
    }
}
