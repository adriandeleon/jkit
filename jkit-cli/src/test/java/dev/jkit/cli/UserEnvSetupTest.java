package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.core.JkitHome;
import dev.jkit.model.JkitException;
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

/** jkit env --install and --uninstall: what they write to the user's environment, and say. */
class UserEnvSetupTest {
    @TempDir
    Path tmp;
    JkitHome home;
    Context ctx;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    String jdk;
    String maven;
    String jdkBin;
    String mavenBin;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tmp.resolve("user/.jkit");
        home = new JkitHome(root);
        // the links only have to exist: install never follows them
        Files.createDirectories(root.resolve("global/jdk/bin"));
        Files.createDirectories(root.resolve("global/maven/bin"));
        jdk = root.resolve("global/jdk").toAbsolutePath().toString();
        maven = root.resolve("global/maven").toAbsolutePath().toString();
        jdkBin = Path.of(jdk, "bin").toString();
        mavenBin = Path.of(maven, "bin").toString();
        ctx = Context.forEnv(new HashMap<>(Map.of("JKIT_HOME", root.toString(), "HOME", tmp.resolve("user").toString())),
                new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private static String path(String... entries) {
        return String.join(File.pathSeparator, entries);
    }

    private String printed(Changes report) {
        report.print(new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void installSetsTheToolsWithGlobalVersionsAndPutsTheirBinsFirst() {
        Changes report = new Changes(ctx);
        Map<String, String> writes = UserEnvSetup.install(home,
                Map.of("Path", path("%USERPROFILE%\\AppData\\Local\\Microsoft\\WindowsApps", "C:\\tools")), report);
        assertThat(writes).containsEntry("JAVA_HOME", jdk).containsEntry("MAVEN_HOME", maven)
                .doesNotContainKeys("GRADLE_HOME", "SBT_HOME", "GRAALVM_HOME") // no global version: left alone
                .containsEntry("Path", path(jdkBin, mavenBin,
                        "%USERPROFILE%\\AppData\\Local\\Microsoft\\WindowsApps", "C:\\tools")); // kept as written
        assertThat(printed(report)).contains("user   JAVA_HOME=").contains("user   PATH + ");
    }

    @Test
    void installThenUninstallGivesBackThePathAsItWas() {
        String before = path("C:\\a", "", "C:\\b") + File.pathSeparator; // an empty entry and a trailing separator
        Map<String, String> after = new HashMap<>(UserEnvSetup.install(home, Map.of("Path", before), new Changes(ctx)));
        assertThat(after.get("Path")).isEqualTo(path(jdkBin, mavenBin, before));
        assertThat(UserEnvSetup.uninstall(home, after, new Changes(ctx))).containsEntry("Path", before);
    }

    @Test
    void installTwiceChangesNothing() {
        Map<String, String> first = UserEnvSetup.install(home, Map.of(), new Changes(ctx));
        Changes report = new Changes(ctx);
        assertThat(UserEnvSetup.install(home, first, report)).isEmpty();
        assertThat(report.isEmpty()).isTrue();
    }

    @Test
    void installMatchesFoldersAsWindowsDoes() {
        // another case and a trailing separator are the same folder: no second PATH entry
        Map<String, String> current = Map.of("JAVA_HOME", jdk.toUpperCase() + File.separator,
                "Path", path(jdkBin.toUpperCase(), mavenBin + File.separator));
        Map<String, String> writes = UserEnvSetup.install(home, current, new Changes(ctx));
        assertThat(writes).containsOnlyKeys("MAVEN_HOME");
    }

    @Test
    void installReplacesAnotherValueAndSaysWhatItWas() {
        Changes report = new Changes(ctx);
        Map<String, String> writes = UserEnvSetup.install(home, Map.of("JAVA_HOME", "C:\\Program Files\\Java\\jdk-11"), report);
        assertThat(writes).containsEntry("JAVA_HOME", jdk);
        assertThat(report.notes()).contains("replaced JAVA_HOME=C:\\Program Files\\Java\\jdk-11, which was set before");
    }

    @Test
    void uninstallRemovesOnlyWhatInstallSet() {
        Map<String, String> current = new HashMap<>(UserEnvSetup.install(home,
                Map.of("Path", path("C:\\tools"), "GRADLE_HOME", "C:\\gradle"), new Changes(ctx)));
        current.put("GRADLE_HOME", "C:\\gradle"); // the user's own
        Changes report = new Changes(ctx);
        Map<String, String> writes = UserEnvSetup.uninstall(home, current, report);
        assertThat(writes).containsEntry("JAVA_HOME", null).containsEntry("MAVEN_HOME", null)
                .containsEntry("Path", "C:\\tools").doesNotContainKey("GRADLE_HOME");
        assertThat(printed(report)).contains("user   JAVA_HOME removed").contains("user   PATH - ");
        current.putAll(writes);
        current.values().removeIf(java.util.Objects::isNull);
        assertThat(UserEnvSetup.uninstall(home, current, new Changes(ctx))).isEmpty();
    }

    @Test
    void shadowingFindsJavaInTheSystemPath() throws Exception {
        Path oracle = Files.createDirectories(tmp.resolve("javapath"));
        Files.writeString(oracle.resolve("java.exe"), "");
        assertThat(UserEnvSetup.shadowing(path(tmp.resolve("empty").toString(), oracle.toString(), "")))
                .containsExactly(oracle + " (java.exe)");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void elsewhereItPointsToTheHook() {
        assertThatThrownBy(() -> UserEnvSetup.run(ctx, true, false)).isInstanceOf(JkitException.class)
                .hasMessageContaining("is for Windows")
                .satisfies(e -> assertThat(((JkitException) e).hints()).anyMatch(h -> h.contains("jkit hook --install")));
    }
}
