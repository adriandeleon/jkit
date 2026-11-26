package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.jkit.core.ShellEnv;
import dev.jkit.model.Envs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ExecTest {
    @TempDir
    Path tmp;
    Path home;
    Path project;
    Map<String, String> env;
    Path temurin21;
    Path zulu17;

    @BeforeEach
    void setUp() throws Exception {
        home = tmp.resolve("user/.jkit");
        project = Files.createDirectories(tmp.resolve("user/src/app"));
        temurin21 = install("jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        zulu17 = install("jdk", "17.0.13+11-zulu", "version=17.0.13+11\nvendor=zulu\n");
        install("maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/jdk"), temurin21);
        env = new HashMap<>(Map.of("JKIT_HOME", home.toString(), "HOME", tmp.resolve("user").toString(),
                "JKIT_JDK_VENDOR", "temurin", "PATH", "/usr/bin"));
    }

    private Path install(String tool, String dir, String properties) throws Exception {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Files.createDirectories(path.resolve("bin"));
        Files.writeString(path.resolve("jkit-install.properties"), "tool=" + tool + "\n" + properties + "fx=false\n");
        return path;
    }

    private ExecCommand.Plan plan(String... choices) throws Exception {
        return ExecCommand.plan(Context.forEnv(env), project, false, ExecCommand.choices(List.of(choices)),
                List.of("java", "-version"));
    }

    @Test
    void choicesTakeAVendorAfterATool() {
        List<ExecCommand.Choice> choices = ExecCommand.choices(List.of("jdk@17", "zulu", "maven@3.9", "gradle"));
        assertThat(choices).extracting(c -> c.spec().toString()).containsExactly("jdk@17", "maven@3.9", "gradle");
        assertThat(choices).extracting(ExecCommand.Choice::vendor).containsExactly("zulu", null, null);
    }

    @Test
    void aWordThatIsNoToolOrVendorIsAUsageError() {
        // "graalvm" right after a JDK is Oracle GraalVM, the vendor, as in jkit use jdk@21 graalvm
        assertThat(ExecCommand.choices(List.of("jdk@21", "graalvm")))
                .containsExactly(new ExecCommand.Choice(ToolSpec.parse("jdk@21"), "graalvm"));
        assertThatThrownBy(() -> ExecCommand.choices(List.of("mvn", "test")))
                .isInstanceOf(JkitException.class).hasMessageContaining("vendor for maven");
        assertThatThrownBy(() -> ExecCommand.choices(List.of("./build.sh")))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(2));
        assertThatThrownBy(() -> ExecCommand.choices(List.of("maven", "zulu")))
                .isInstanceOf(JkitException.class).hasMessageContaining("'zulu' is not a tool");
    }

    @Test
    void withoutToolsItIsWhatJkitEnvGives() throws Exception {
        ExecCommand.Plan plan = plan();
        assertThat(plan.variables()).containsEntry("JAVA_HOME", home.resolve("global/jdk").toString());
        assertThat(plan.variables().get("PATH")).startsWith(home.resolve("global/jdk/bin").toString());
    }

    @Test
    void namedToolsOverrideTheProjectAndGlobals() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\n");
        ExecCommand.Plan plan = plan("jdk@17", "zulu", "maven");
        assertThat(plan.variables()).containsEntry("JAVA_HOME", zulu17.toString())
                .containsEntry("MAVEN_HOME", home.resolve("tools/maven/3.9.11").toString());
        assertThat(plan.variables().get("PATH")).isEqualTo(String.join(File.pathSeparator,
                zulu17.resolve("bin").toString(), home.resolve("tools/maven/3.9.11/bin").toString(), "/usr/bin"));
    }

    @Test
    void aNamedToolCoversAPinThatIsNotInstalled() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"11\"\nmaven = \"3.9\"\n");
        ExecCommand.Plan plan = plan("jdk@17", "zulu");
        assertThat(plan.variables()).containsEntry("JAVA_HOME", zulu17.toString())
                .containsEntry("MAVEN_HOME", home.resolve("tools/maven/3.9.11").toString());

        // a missing pin for a tool not named is still an error, with a hint that fits exec
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"11\"\n");
        assertThatThrownBy(() -> plan("maven"))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).hints()).contains(
                        "or ignore the project file: jkit exec --no-project <tools> -- <command>"));
    }

    @Test
    void aToolThatIsNotInstalledExitsThree() {
        assertThatThrownBy(() -> plan("jdk@11"))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(JkitException.NOT_INSTALLED))
                .hasMessageContaining("no installed JDK matches 11");
    }

    @Test
    void anotherVendorsInstallIsOfferedAsAnExecCommand() {
        assertThatThrownBy(() -> plan("jdk@17"))
                .isInstanceOf(JkitException.class)
                .hasMessage("no installed JDK matches 17 from temurin (default vendor)")
                .satisfies(e -> assertThat(((JkitException) e).hints()).containsExactly(
                        "install it:          jkit install jdk@17",
                        "or use installed:    jkit exec jdk@17 zulu -- <command>"));
    }

    @Test
    void theCallingShellsStateDoesNotLeakIn() throws Exception {
        env.put(ShellEnv.stateVariable(Tool.MAVEN), home.resolve("tools/maven/3.9.11").toString());
        env.put("MAVEN_HOME", home.resolve("tools/maven/3.9.11").toString());
        env.put("JKIT_ENV_FILE", tmp.resolve("env").toString());
        env.put("GRADLE_HOME", "/opt/gradle");
        Map<String, String> child = ExecCommand.childEnv(env, plan());
        assertThat(child).doesNotContainKeys(ShellEnv.stateVariable(Tool.MAVEN), "MAVEN_HOME", "JKIT_ENV_FILE")
                .containsEntry("GRADLE_HOME", "/opt/gradle") // not jkit's: left alone
                .containsEntry("HOME", tmp.resolve("user").toString());
    }

    @Test
    void windowsPathIsFoundWhateverItsCase() throws Exception {
        // Windows names it "Path"; System.getenv() is case-sensitive, so jkit reads it through Envs
        env.remove("PATH");
        env.put("Path", "/usr/bin");
        env = Envs.of(env, true);
        ExecCommand.Plan plan = plan("jdk@21");
        assertThat(plan.variables().get("PATH")).endsWith(File.pathSeparator + "/usr/bin");
        Map<String, String> child = ExecCommand.childEnv(env, plan);
        assertThat(child.keySet().stream().filter(k -> k.equalsIgnoreCase("path")).count()).isEqualTo(1);
        assertThat(child.get("Path")).isEqualTo(plan.variables().get("PATH"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks
    void aGlobalLinkIsNeverPointedAtAnInstallRemovedMeanwhile() throws Exception {
        // jkit use --global resolved zulu 17, then a jkit remove took it away before the link was set
        Context ctx = Context.forEnv(env);
        Files.delete(zulu17.resolve("jkit-install.properties"));
        assertThatThrownBy(() -> Resolve.pointGlobal(ctx, home.resolve("global/jdk"), zulu17))
                .isInstanceOf(JkitException.class).hasMessageContaining("removed by another jkit");
        assertThat(Files.readSymbolicLink(home.resolve("global/jdk"))).isEqualTo(temurin21);
        Resolve.pointGlobal(ctx, home.resolve("global/jdk"), temurin21); // still works for one that's there
        // and doesn't wait for an install running meanwhile, which holds the store lock for its download
        try (AutoCloseable install = ctx.store.lock()) {
            Resolve.pointGlobal(ctx, home.resolve("global/jdk"), temurin21);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theCommandIsFoundOnTheNewPath() throws Exception {
        Path java = Files.writeString(zulu17.resolve("bin/java"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        String path = zulu17.resolve("bin") + File.pathSeparator + "/usr/bin";
        assertThat(ExecCommand.executable("java", path, project)).isEqualTo(java.toString());
        assertThat(ExecCommand.executable("./run.sh", path, project)).isEqualTo("./run.sh");
        assertThatThrownBy(() -> ExecCommand.executable("no-such-command-jkit", path, project))
                .isInstanceOf(JkitException.class)
                .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(127));
    }

    @Test
    void anExitCodeFromASignalNamesItOnUnixOnly() {
        assertThat(Signals.endedBy(130, false)).isEqualTo("INT");
        assertThat(Signals.endedBy(143, false)).isEqualTo("TERM");
        assertThat(Signals.endedBy(129, false)).isEqualTo("HUP");
        assertThat(Signals.endedBy(1, false)).isNull();
        assertThat(Signals.endedBy(137, false)).isNull(); // KILL: jkit only passes on what a shell acts on
        assertThat(Signals.endedBy(130, true)).isNull();
    }

    @Test
    void onlyACtrlCThatEndedTheCommandEndsJkitFromSigint() {
        assertThat(Signals.ctrlC(130, true, false)).isTrue();
        assertThat(Signals.ctrlC(130, false, false)).isFalse(); // the command exited 130, or got SIGINT alone
        assertThat(Signals.ctrlC(0, true, false)).isFalse(); // a REPL that handled it
        assertThat(Signals.ctrlC(143, true, false)).isFalse();
        assertThat(Signals.ctrlC(130, true, true)).isFalse();
        // a container's init: the kernel drops a signal PID 1 sends itself
        assertThat(Signals.canRaise(1, false)).isFalse();
        assertThat(Signals.canRaise(4242, false)).isTrue();
        assertThat(Signals.canRaise(4242, true)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onlyTheHooksOwnEnvFileIsDeletedOnCtrlC() throws Exception {
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path ours = Files.createFile(temp.resolve("jkit-env.Ab12Cd"));
        Map<String, String> e = new HashMap<>(Map.of("TMPDIR", temp + "/", "JKIT_ENV_FILE", ours.toString()));
        assertThat(Signals.hookEnvFile(e)).isEqualTo(ours);
        e.put("JKIT_ENV_FILE", Files.createFile(temp.resolve("notes.txt")).toString());
        assertThat(Signals.hookEnvFile(e)).isNull();
        e.put("JKIT_ENV_FILE", Files.createFile(tmp.resolve("jkit-env.Ab12Cd")).toString()); // not in TMPDIR
        assertThat(Signals.hookEnvFile(e)).isNull();
        Files.createSymbolicLink(temp.resolve("jkit-env.Link12"), ours);
        e.put("JKIT_ENV_FILE", temp.resolve("jkit-env.Link12").toString());
        assertThat(Signals.hookEnvFile(e)).isNull();
        e.remove("JKIT_ENV_FILE");
        assertThat(Signals.hookEnvFile(e)).isNull();
    }

    /**
     * Runs jkit exec in a JVM of its own under perl, which reports the signal that ended it (Java
     * can't tell a death by SIGINT from exit code 130): a bash script stops on Ctrl-C only on the first.
     */
    private String endedBy(String script) throws Exception {
        Path perl = Path.of("/usr/bin/perl");
        assumeTrue(Files.isExecutable(perl), "no perl to tell how a process ended");
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // SIGINT at its default, as in an interactive shell, so jkit handles it
        ProcessBuilder pb = new ProcessBuilder(perl.toString(), "-e",
                "$SIG{INT} = 'DEFAULT'; system(@ARGV); print(($? & 127) . ' ' . ($? >> 8))",
                jvm, "-cp", System.getProperty("java.class.path"), "dev.jkit.cli.Main", "exec", "--no-project", "--",
                "/bin/sh", "-c", script).directory(project.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("JKIT_HOME", home.toString());
        pb.environment().put("HOME", tmp.resolve("user").toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor();
        return out;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCtrlCThatEndsTheCommandEndsJkitWithSigint() throws Exception {
        // a Ctrl-C reaches jkit (the command's parent) and the command: jkit ends from SIGINT
        // (signal 2, no exit code), and the command wasn't left ignoring SIGINT
        assertThat(endedBy("kill -INT $PPID; sleep 1; kill -INT $$; echo survived")).isEqualTo("2 0");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void otherwiseTheCommandsExitCodeComesThrough() throws Exception {
        // SIGINT to the command alone, or an exit code that looks like a signal: only the code
        assertThat(endedBy("kill -INT $$")).isEqualTo("0 130");
        assertThat(endedBy("exit 130")).isEqualTo("0 130");
        assertThat(endedBy("exit 129")).isEqualTo("0 129");
        assertThat(endedBy("kill -TERM $$")).isEqualTo("0 143");
        assertThat(endedBy("exit 3")).isEqualTo("0 3");
    }
}
