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
    void ltsOfflineIsTheNewestLtsReleaseInstalled() throws Exception {
        Context ctx = Context.forEnv(Map.of("JKIT_HOME", home.toString()));
        assertThatThrownBy(() -> Resolve.lts(ctx, ToolSpec.parse("jdk@lts"), false))
                .isInstanceOf(JkitException.class).hasMessageContaining("no LTS jdk is installed");
        install("17.0.13+11", false);
        install("21.0.9+10", false);
        install("23.0.2+7", false); // newer, but not LTS
        assertThat(Resolve.lts(ctx, ToolSpec.parse("jdk@lts"), false).version()).isEqualTo("21");
        assertThat(Resolve.lts(ctx, ToolSpec.parse("jdk@21"), false).version()).isEqualTo("21"); // others untouched
    }

    private static dev.jkit.sources.FoojaySource.Pkg pkg(String vendor, String version, String term) {
        return new dev.jkit.sources.FoojaySource.Pkg("id", Vendor.of(vendor), dev.jkit.model.Version.parse(version).part(0),
                dev.jkit.model.Version.parse(version), "jdk.tar.gz", "tar.gz", term, true);
    }

    /** jdk@lts zulu online: zulu's own newest LTS, not one only another vendor has shipped yet. */
    @Test
    void ltsOnlineIsTheVendorsOwnNewestLts() {
        List<dev.jkit.sources.FoojaySource.Pkg> builds = List.of(
                pkg("temurin", "25.0.1", "lts"), pkg("temurin", "21.0.9", "lts"), pkg("temurin", "24.0.2", "sts"),
                pkg("zulu", "21.0.9", "lts"), pkg("zulu", "17.0.17", "lts"), pkg("zulu", "24.0.2", "sts"),
                pkg("corretto", "17.0.17", ""), pkg("corretto", "23.0.2", ""));
        assertThat(Resolve.newestLts(builds, Vendor.of("temurin"))).isEqualTo(25);
        assertThat(Resolve.newestLts(builds, Vendor.of("zulu"))).isEqualTo(21);
        assertThat(Resolve.newestLts(builds, Vendor.of("corretto"))).isEqualTo(17); // no term given: the LTS rule
        assertThat(Resolve.newestLts(builds, Vendor.of("liberica"))).isNull();
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

    /** Caches foojay's list of releases (27, 25, 21) the way search leaves it. */
    private void cacheMajors() throws Exception {
        String majors = """
                {"result":[{"major_version":27,"term_of_support":"STS","maintained":true},
                {"major_version":25,"term_of_support":"LTS","maintained":true},
                {"major_version":21,"term_of_support":"LTS","maintained":true}]}""";
        dev.jkit.sources.Http answer = new dev.jkit.sources.Http() {
            @Override
            public String get(java.net.URI uri) {
                return majors;
            }

            @Override
            public void download(java.net.URI uri, Path target, Progress progress) {
                throw new UnsupportedOperationException();
            }
        };
        new dev.jkit.sources.FoojaySource(new dev.jkit.sources.CachingHttp(answer, home.resolve("index"),
                java.time.Duration.ofHours(24), java.time.Clock.systemUTC(), false, false),
                dev.jkit.sources.FoojaySource.DEFAULT_BASE).majors();
    }

    @Test
    void aMajorThatIsNoReleaseIsNotSuggestedForInstall() throws Exception {
        // nothing cached: the install hint as always
        assertThatThrownBy(() -> installed("jdk@99", false))
                .satisfies(e -> assertThat(((JkitException) e).hints()).anyMatch(h -> h.contains("jkit install jdk@99")));
        cacheMajors();
        assertThatThrownBy(() -> installed("jdk@99", false))
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .noneMatch(h -> h.contains("jkit install"))
                        .anyMatch(h -> h.contains("there is no JDK 99 release yet (the newest is 27)")));
        assertThatThrownBy(() -> installed("jdk@22", false))
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .anyMatch(h -> h.contains("there is no JDK 22 release")));
        // a real release that isn't installed
        assertThatThrownBy(() -> installed("jdk@25", false))
                .satisfies(e -> assertThat(((JkitException) e).hints()).anyMatch(h -> h.contains("jkit install jdk@25")));
    }
}
