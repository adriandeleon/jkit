package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.JkitException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class JkitHomeTest {
    @TempDir
    Path tmp;

    @Test
    void aRelativeJkitHomeIsRefused() {
        // relative to every folder you cd into: a cloned repo could bring its own tools
        assertThatThrownBy(() -> JkitHome.fromEnv(Map.of("JKIT_HOME", "jkit-home")))
                .isInstanceOf(JkitException.class).hasMessageContaining("absolute");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theDefaultHomeFollowsHome() {
        assertThat(JkitHome.fromEnv(Map.of("HOME", tmp.toString())).root()).isEqualTo(tmp.resolve(".jkit"));
    }

    @Test
    void creatingAnExistingHomeIsFine() throws Exception {
        JkitHome home = new JkitHome(tmp.resolve("h"));
        home.create();
        Files.delete(home.root());
        Files.createDirectory(home.root()); // as if another jkit made it at the same moment
        home.create();
        assertThat(home.root()).isDirectory();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aToolsFolderEveryoneCanWriteIsRefused() throws Exception {
        JkitHome home = new JkitHome(tmp.resolve("h"));
        home.create();
        home.requireSafe();
        Files.createDirectories(home.toolsRoot());
        Files.setPosixFilePermissions(home.toolsRoot(), PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(home::requireSafe).isInstanceOf(JkitException.class)
                .hasMessageContaining("tools is writable by every user");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aParentEveryoneCanWriteIsRefusedUnlessSticky() throws Exception {
        Path shared = Files.createDirectory(tmp.resolve("shared"));
        JkitHome home = new JkitHome(shared.resolve("h"));
        home.create();
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(home::requireSafe).isInstanceOf(JkitException.class).hasMessageContaining("anyone could replace it");
        Files.setAttribute(shared, "unix:mode", 01777); // like /tmp
        home.requireSafe();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void ownershipIsComparedByUid() throws Exception {
        Path mine = Files.createDirectory(tmp.resolve("mine"));
        assertThat(Owners.mine(mine)).isTrue();
        assertThat(Owners.mineOrRoot(Path.of("/"))).isTrue(); // root-owned
        assertThat(Owners.myUid()).isGreaterThanOrEqualTo(0);
    }
}
