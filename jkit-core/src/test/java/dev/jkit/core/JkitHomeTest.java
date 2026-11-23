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
    void theFirstRunIsCheckedBeforeAnythingIsCreated() throws Exception {
        Path shared = Files.createDirectory(tmp.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        JkitHome home = new JkitHome(shared.resolve("new/.jkit"));
        assertThatThrownBy(home::requireSafe).isInstanceOf(JkitException.class).hasMessageContaining("anyone could replace it");
        assertThatThrownBy(home::create).isInstanceOf(JkitException.class);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aSymlinkAboveTheHomeIsCheckedWhereItLeads() throws Exception {
        Path shared = Files.createDirectory(tmp.resolve("shared"));
        Path sub = Files.createDirectory(shared.resolve("sub"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), sub);
        JkitHome home = new JkitHome(link.resolve(".jkit"));
        assertThatThrownBy(home::requireSafe).isInstanceOf(JkitException.class).hasMessageContaining("anyone could replace it");
    }

    @Test
    void quotedOrInvalidPathsFromTheEnvironmentAreHandled() {
        assertThat(Fs.path("\"" + tmp + "\"")).isEqualTo(tmp);
        assertThat(Fs.path("  ")).isNull();
        assertThat(Fs.path(null)).isNull();
        assertThat(Fs.path("bad\u0000path")).isNull();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void ownershipIsComparedByUid() throws Exception {
        Path mine = Files.createDirectory(tmp.resolve("mine"));
        assertThat(Owners.mine(mine)).isTrue();
        assertThat(Owners.mineOrRoot(Path.of("/"))).isTrue(); // root-owned
        if (Files.exists(Path.of("/proc/self"))) { // Linux reads the uid; macOS compares names
            assertThat(Owners.myUid()).isGreaterThanOrEqualTo(0);
        }
    }
}
