package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileTest {
    @TempDir
    Path tmp;

    @Test
    void replacesTheTextAndLeavesNoTemporaryFileBehind() throws Exception {
        Path rc = Files.writeString(tmp.resolve(".bashrc"), "old\n");
        assertThat(AtomicFile.write(rc, "new\n", StandardCharsets.UTF_8, true)).isEqualTo(rc);
        assertThat(Files.readString(rc)).isEqualTo("new\n");
        Path created = AtomicFile.write(tmp.resolve("config.toml"), "[jdk]\n", StandardCharsets.UTF_8, true);
        assertThat(Files.readString(created)).isEqualTo("[jdk]\n");
        try (var files = Files.list(tmp)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(".bashrc", "config.toml");
        }
    }

    @Test
    void textTheCharsetCantHoldIsAnErrorAndTheFileStaysAsItWas() throws Exception {
        Path rc = Files.writeString(tmp.resolve(".zshrc"), "old\n");
        assertThatThrownBy(() -> AtomicFile.write(rc, "José ☕\n", StandardCharsets.ISO_8859_1, true))
                .isInstanceOf(CharacterCodingException.class);
        assertThat(Files.readString(rc)).isEqualTo("old\n");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void keepsTheFilesPermissionsAndANewFileIs644() throws Exception {
        Path rc = Files.writeString(tmp.resolve(".bashrc"), "old\n");
        Files.setPosixFilePermissions(rc, PosixFilePermissions.fromString("rw-------"));
        AtomicFile.write(rc, "new\n", StandardCharsets.UTF_8, true);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(rc))).isEqualTo("rw-------");
        Path created = AtomicFile.write(tmp.resolve("jkit.toml"), "x\n", StandardCharsets.UTF_8, false);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(created))).isEqualTo("rw-r--r--");
    }

    /** A dotfile manager's link stays a link, and the file it points to gets the text. */
    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks
    void writesThroughALinkAndKeepsIt() throws Exception {
        Path dotfiles = Files.createDirectories(tmp.resolve("dotfiles"));
        Path real = Files.writeString(dotfiles.resolve("bashrc"), "old\n");
        Path rc = Files.createSymbolicLink(tmp.resolve(".bashrc"), Path.of("dotfiles/bashrc"));
        assertThat(AtomicFile.write(rc, "new\n", StandardCharsets.UTF_8, true)).isEqualTo(real.toRealPath());
        assertThat(Files.isSymbolicLink(rc)).isTrue();
        assertThat(Files.readString(real)).isEqualTo("new\n");
        try (var files = Files.list(dotfiles)) {
            assertThat(files).hasSize(1);
        }

        // a link to a file that isn't there yet: that file is created
        Path dangling = Files.createSymbolicLink(tmp.resolve(".zshrc"), Path.of("dotfiles/zshrc"));
        AtomicFile.write(dangling, "z\n", StandardCharsets.UTF_8, true);
        assertThat(Files.isSymbolicLink(dangling)).isTrue();
        assertThat(Files.readString(dotfiles.resolve("zshrc"))).isEqualTo("z\n");
    }

    /** jkit.toml: a link put there is replaced, and what it pointed at is left alone. */
    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks
    void withoutFollowingALinkIsReplacedAndItsTargetUntouched() throws Exception {
        Path secret = Files.writeString(tmp.resolve("profile"), "keep\n");
        Path toml = Files.createSymbolicLink(tmp.resolve("jkit.toml"), secret);
        AtomicFile.write(toml, "[tools]\n", StandardCharsets.UTF_8, false);
        assertThat(Files.isSymbolicLink(toml)).isFalse();
        assertThat(Files.readString(toml)).isEqualTo("[tools]\n");
        assertThat(Files.readString(secret)).isEqualTo("keep\n");
    }
}
