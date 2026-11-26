package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import dev.jkit.sources.FoojaySource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallCommandTest {
    @TempDir
    Path home;

    @Test
    void aBuildFromAVendorThatStoppedUpdatingItsReleaseGetsAWarning() {
        Context ctx = Context.forEnv(new HashMap<>(Map.of("JKIT_HOME", home.toString())));
        Artifact openjdk = new Artifact(Tool.JDK, Version.parse("21.0.2+13"), Vendor.of("openjdk"),
                URI.create("https://example.invalid/o.tar.gz"), "o.tar.gz", "tar.gz", "sha256", "ab", false);
        FoojaySource.Behind behind = new FoojaySource.Behind(Version.parse("21.0.9+10"),
                List.of("corretto", "temurin", "zulu", "liberica"));
        // temurin, the default vendor, has the newest: plain jkit install jdk@21 gets it
        assertThat(InstallCommand.behindWarning(ctx, Tool.JDK, openjdk, behind)).isEqualTo(
                "openjdk stopped updating 21 at 21.0.2+13; the newest 21 is 21.0.9+10 (corretto, temurin, zulu, …):"
                        + " jkit install jdk@21");
        assertThat(InstallCommand.behindWarning(ctx, Tool.JDK, openjdk,
                new FoojaySource.Behind(Version.parse("21.0.9+10"), List.of("zulu")))).endsWith(": jkit install jdk@21 zulu");
        // other tools, and offline with nothing cached: no comparison, no warning
        assertThat(InstallCommand.behind(ctx, Tool.MAVEN, openjdk)).isNull();
        assertThat(InstallCommand.behind(ctx, Tool.JDK, openjdk)).isNull();
    }

    /** After a lost install race, the winner may have made it the default: then that's no hint to give. */
    @Test
    void aDefaultSetByAnotherJkitIsSeen() throws Exception {
        Context ctx = Context.forEnv(new HashMap<>(Map.of("JKIT_HOME", home.toString())));
        Path jdk21 = Files.createDirectories(home.resolve("tools/jdk/21"));
        Path jdk17 = Files.createDirectories(home.resolve("tools/jdk/17"));
        Path link = ctx.home.global(Tool.JDK);
        assertThat(InstallCommand.isDefault(ctx, link, jdk21)).isFalse(); // no link yet

        Files.createDirectories(link.getParent());
        ctx.os.pointLink(link, jdk21);

        assertThat(InstallCommand.isDefault(ctx, link, jdk21)).isTrue();
        assertThat(InstallCommand.isDefault(ctx, link, jdk17)).isFalse();
    }
}
