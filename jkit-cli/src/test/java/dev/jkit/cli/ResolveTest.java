package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.core.Installed;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolveTest {

    @TempDir
    Path home;

    private void install(String version, boolean fx) throws Exception {
        Path dir = home.resolve("tools").resolve(Tool.JDK.storeDir())
                .resolve(version + "-zulu" + (fx ? "-fx" : ""));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jkit-install.properties"),
                "version=" + version + "\nvendor=zulu\nfx=" + fx + "\n");
    }

    private List<String> installed(String spec, boolean fx) throws Exception {
        Context ctx = Context.forEnv(Map.of("JKIT_HOME", home.toString()));
        return Resolve.installed(ctx, ToolSpec.parse(spec), Vendor.of("zulu"), "zulu", fx).stream()
                .map(Installed::label).toList();
    }

    @Test
    void plainBuildsWinUnlessFxIsAsked() throws Exception {
        install("21.0.5+11", false);
        install("21.0.9+10", true);
        install("21.0.9+10", false);

        assertThat(installed("jdk@21", false)).containsExactly("21.0.9+10 zulu", "21.0.5+11 zulu");
        assertThat(installed("jdk@21", true)).containsExactly("21.0.9+10 zulu fx");
    }

    @Test
    void javaFxBuildsMatchWhenNoPlainBuildDoes() throws Exception {
        install("21.0.9+10", true);

        assertThat(installed("jdk@21", false)).containsExactly("21.0.9+10 zulu fx");
    }

    @Test
    void fxWithoutJavaFxBuildsSuggestsInstallingOne() throws Exception {
        install("21.0.9+10", false);

        assertThatThrownBy(() -> installed("jdk@21", true))
                .isInstanceOf(JkitException.class)
                .hasMessageContaining("with JavaFX")
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .anySatisfy(h -> assertThat(h).contains("jkit install jdk@21 zulu --fx")));
    }
}
