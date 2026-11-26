package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallCommandTest {
    @TempDir
    Path home;

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
