package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.core.Installed;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradeTest {

    @TempDir
    Path home;

    /** A marker as written before jkit recorded JavaFX: no fx property. */
    private void install(String storeDir, String version, String vendor) throws Exception {
        install(storeDir, version, vendor, "");
    }

    private void install(String storeDir, String version, String vendor, String extra) throws Exception {
        Path dir = home.resolve("tools").resolve(storeDir)
                .resolve((vendor.isEmpty() ? version : version + "-" + vendor) + (extra.isEmpty() ? "" : "-x"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jkit-install.properties"),
                "version=" + version + "\nvendor=" + vendor + "\n" + extra);
    }

    private Map<UpgradeCommand.Line, List<Installed>> lines(String spec, String vendor) throws Exception {
        UpgradeCommand cmd = new UpgradeCommand();
        cmd.spec = spec == null ? null : ToolSpec.parse(spec);
        cmd.vendorText = vendor;
        return cmd.lines(Context.forEnv(Map.of("JKIT_HOME", home.toString())));
    }

    @Test
    void groupsByToolMajorAndVendorNewestFirst() throws Exception {
        String jdk = Tool.JDK.storeDir();
        install(jdk, "21.0.5+11", "temurin");
        install(jdk, "21.0.8+9", "temurin");
        install(jdk, "21.0.4+7", "zulu");
        install(jdk, "17.0.12+7", "temurin");
        install(jdk, "26-ea+3", "temurin");
        install(Tool.MAVEN.storeDir(), "3.9.9", "");

        var lines = lines(null, null);

        Vendor temurin = Vendor.of("temurin");
        assertThat(lines.keySet()).containsExactlyInAnyOrder(
                new UpgradeCommand.Line(Tool.JDK, 21, temurin, false),
                new UpgradeCommand.Line(Tool.JDK, 21, Vendor.of("zulu"), false),
                new UpgradeCommand.Line(Tool.JDK, 17, temurin, false),
                new UpgradeCommand.Line(Tool.MAVEN, 3, null, false));
        assertThat(lines.get(new UpgradeCommand.Line(Tool.JDK, 21, temurin, false)))
                .extracting(i -> i.version().raw()).containsExactly("21.0.8+9", "21.0.5+11");
    }

    @Test
    void filtersByToolMajorAndVendor() throws Exception {
        String jdk = Tool.JDK.storeDir();
        install(jdk, "21.0.5+11", "temurin");
        install(jdk, "21.0.4+7", "zulu");
        install(jdk, "17.0.12+7", "zulu");
        install(Tool.MAVEN.storeDir(), "3.9.9", "");

        assertThat(lines("jdk@21", "zulu").keySet())
                .containsExactly(new UpgradeCommand.Line(Tool.JDK, 21, Vendor.of("zulu"), false));
        assertThat(lines("maven", null).keySet())
                .containsExactly(new UpgradeCommand.Line(Tool.MAVEN, 3, null, false));
    }

    @Test
    void javaFxBuildsAreTheirOwnLine() throws Exception {
        String jdk = Tool.JDK.storeDir();
        install(jdk, "21.0.5+11", "zulu", "fx=false\n");
        install(jdk, "21.0.4+7", "zulu", "fx=true\n");

        Vendor zulu = Vendor.of("zulu");
        var lines = lines(null, null);
        assertThat(lines.keySet()).containsExactlyInAnyOrder(
                new UpgradeCommand.Line(Tool.JDK, 21, zulu, false),
                new UpgradeCommand.Line(Tool.JDK, 21, zulu, true));
        assertThat(lines.get(new UpgradeCommand.Line(Tool.JDK, 21, zulu, true)))
                .extracting(i -> i.version().raw()).containsExactly("21.0.4+7");
        assertThat(new UpgradeCommand.Line(Tool.JDK, 21, zulu, true)).hasToString("jdk@21 zulu --fx");
    }

    @Test
    void fxOptionMarksOlderInstallsAsJavaFx() throws Exception {
        install(Tool.JDK.storeDir(), "21.0.4+7", "zulu");
        install(Tool.MAVEN.storeDir(), "3.9.9", "");
        UpgradeCommand cmd = new UpgradeCommand();
        cmd.spec = ToolSpec.parse("jdk");
        cmd.fx = true;
        assertThat(cmd.lines(Context.forEnv(Map.of("JKIT_HOME", home.toString()))).keySet())
                .containsExactly(new UpgradeCommand.Line(Tool.JDK, 21, Vendor.of("zulu"), true));
    }
}
