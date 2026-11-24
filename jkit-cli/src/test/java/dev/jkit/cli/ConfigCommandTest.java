package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** jkit config: list, get, set and unset over config.toml, and that changes are reported. */
class ConfigCommandTest {
    @TempDir
    Path tmp;
    Path home;
    Map<String, String> env;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        home = tmp.resolve(".jkit");
        env = new HashMap<>(Map.of("HOME", tmp.toString(), "JKIT_HOME", home.toString()));
    }

    private Context ctx() {
        out.reset();
        return Context.forEnv(env, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private int set(String key, String value) throws Exception {
        ConfigCommand.Set cmd = new ConfigCommand.Set();
        cmd.key = key;
        cmd.value = value;
        return cmd.run(ctx());
    }

    private int unset(String key) throws Exception {
        ConfigCommand.Unset cmd = new ConfigCommand.Unset();
        cmd.key = key;
        return cmd.run(ctx());
    }

    private String get(String key) {
        ConfigCommand.Get cmd = new ConfigCommand.Get();
        cmd.key = key;
        cmd.run(ctx());
        return output().strip();
    }

    @Test
    void listShowsDefaultsWithNoFile() {
        ConfigCommand.list(ctx(), false);
        assertThat(output()).contains("jdk.vendor").contains("temurin").contains("default")
                .contains("shell.auto_switch").contains("false");
        assertThat(home).doesNotExist(); // reading never creates anything
    }

    @Test
    void setCreatesTheFileAndSaysSo() throws Exception {
        set("shell.auto_switch", "on");
        assertThat(Files.readString(home.resolve("config.toml"))).isEqualTo("[shell]\nauto_switch = true\n");
        assertThat(output()).contains("set shell.auto_switch = true").contains("config.toml")
                .contains("new shells switch");
        assertThat(get("shell.auto_switch")).isEqualTo("true");
    }

    @Test
    void setTheGraalVmVendorChecksItHasNativeImage() throws Exception {
        set("graalvm.vendor", "mandrel");
        assertThat(get("graalvm.vendor")).isEqualTo("mandrel");
        assertThatThrownBy(() -> set("graalvm.vendor", "zulu")).isInstanceOf(JkitException.class)
                .hasMessageContaining("native-image");
    }

    @Test
    void setTheSameValueChangesNothing() throws Exception {
        set("shell.auto_switch", "true");
        Path config = home.resolve("config.toml");
        String before = Files.readString(config);
        set("shell.auto_switch", "yes");
        assertThat(output()).contains("already true").contains("nothing changed");
        assertThat(Files.readString(config)).isEqualTo(before);
    }

    @Test
    void setKeepsCommentsAndOtherSettings() throws Exception {
        Files.createDirectories(home);
        Path config = home.resolve("config.toml");
        Files.writeString(config, "# mine\n[graalvm]\nvendor = \"mandrel\" # for work\n");
        set("graalvm.vendor", "liberica-nik");
        assertThat(Files.readString(config)).isEqualTo("# mine\n[graalvm]\nvendor = \"liberica-nik\" # for work\n");
    }

    @Test
    void anEnvironmentVariableWinsAndIsReported() throws Exception {
        env.put("JKIT_AUTO_SWITCH", "0");
        set("shell.auto_switch", "true");
        assertThat(output()).contains("JKIT_AUTO_SWITCH=0 is set and wins");
        assertThat(get("shell.auto_switch")).isEqualTo("false");
        ConfigCommand.list(ctx(), false);
        assertThat(output()).contains("from JKIT_AUTO_SWITCH").contains("says true");
    }

    @Test
    void unsetRemovesItAndTheDefaultApplies() throws Exception {
        set("shell.auto_switch", "true");
        unset("shell.auto_switch");
        assertThat(output()).contains("removed shell.auto_switch").contains("the default, false");
        assertThat(Files.readString(home.resolve("config.toml"))).isEmpty();
        unset("shell.auto_switch");
        assertThat(output()).contains("doesn't set shell.auto_switch");
    }

    @Test
    void unknownKeysAndValuesAreRefused() {
        assertThatThrownBy(() -> set("jdk.vendr", "zulu")).isInstanceOf(JkitException.class)
                .hasMessageContaining("unknown setting 'jdk.vendr'")
                .satisfies(e -> assertThat(((JkitException) e).hints()).anyMatch(h -> h.contains("jdk.vendor")));
        assertThatThrownBy(() -> set("shell.auto_switch", "maybe")).isInstanceOf(JkitException.class)
                .hasMessageContaining("true or false");
        assertThatThrownBy(() -> set("jdk.vendor", "../evil")).isInstanceOf(JkitException.class)
                .hasMessageContaining("isn't a vendor name");
        // every one is a bad argument, like an unknown vendor: exit 2
        for (String[] bad : new String[][] {{"jdk.vendr", "zulu"}, {"shell.auto_switch", "maybe"}, {"jdk.vendor", "../evil"}}) {
            assertThatThrownBy(() -> set(bad[0], bad[1]))
                    .satisfies(e -> assertThat(((JkitException) e).exitCode()).isEqualTo(2));
        }
    }

    @Test
    void jsonListsEverySetting() {
        ConfigCommand.list(ctx(), true);
        assertThat(output()).contains("\"schemaVersion\"").contains("\"key\" : \"jdk.vendor\"")
                .contains("\"source\" : \"default\"").contains("\"envVariable\" : \"JKIT_JDK_VENDOR\"");
    }

    @Test
    void setAndUnsetCanRepairAWrongValue() throws Exception {
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.toml"), "[jdk]\nvendor = \"zulu\"\n[shell]\nauto_switch = \"yes\"\n");
        out.reset();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);

        // listing: the file's zulu isn't what jkit applies while the file is broken
        Context broken = Context.lenient(env, print);
        assertThat(ConfigCommand.effective(broken)).filteredOn(e -> e.setting().key().equals("jdk.vendor"))
                .singleElement().satisfies(e -> {
                    assertThat(e.value()).isEqualTo("temurin");
                    assertThat(e.source()).isEqualTo("default");
                    assertThat(e.inFile()).isEqualTo("zulu");
                });
        ConfigCommand.list(broken, true);
        assertThat(output()).contains("\"error\" : \"invalid").contains("auto_switch must be true or false");

        ConfigCommand.Set set = new ConfigCommand.Set();
        set.key = "shell.auto_switch";
        set.value = "true";
        assertThat(set.run(Context.forConfigEdit(env, print, true))).isZero();
        assertThat(home.resolve("config.toml")).content().contains("auto_switch = true").contains("vendor = \"zulu\"");
        assertThat(get("jdk.vendor")).isEqualTo("zulu"); // a strict context loads it again

        Files.writeString(home.resolve("config.toml"), "[shell]\nauto_switch = 3\n");
        ConfigCommand.Unset unset = new ConfigCommand.Unset();
        unset.key = "shell.auto_switch";
        assertThat(unset.run(Context.forConfigEdit(env, print, true))).isZero();
        assertThat(home.resolve("config.toml")).content().doesNotContain("auto_switch");
    }
}
