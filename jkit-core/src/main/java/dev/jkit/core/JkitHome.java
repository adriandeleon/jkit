package dev.jkit.core;

import dev.jkit.model.Tool;
import java.nio.file.Path;
import java.util.Map;

/** The ~/.jkit layout. */
public record JkitHome(Path root) {

    public static JkitHome fromEnv(Map<String, String> env) {
        String override = env.get("JKIT_HOME");
        if (override != null && !override.isBlank()) {
            return new JkitHome(Path.of(override));
        }
        return new JkitHome(Path.of(System.getProperty("user.home"), ".jkit"));
    }

    public JkitHome {
        root = root.toAbsolutePath().normalize();
    }

    public Path toolsRoot() { return root.resolve("tools"); }

    public Path tools(Tool tool) { return toolsRoot().resolve(tool.storeDir()); }

    public Path global(Tool tool) { return root.resolve("global").resolve(tool.id()); }

    public Path tmp() { return root.resolve("tmp"); }

    public Path index() { return root.resolve("index"); }

    public Path lockFile() { return root.resolve(".lock"); }

    public Path config() { return root.resolve("config.toml"); }

    private static final boolean POSIX =
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * Creates the home if needed, readable only by this user: its {@code bin} folders go first on
     * PATH, so nobody else may put anything in it.
     */
    public void create() throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(root)) {
            return;
        }
        if (root.getParent() != null) {
            java.nio.file.Files.createDirectories(root.getParent());
        }
        if (POSIX) {
            java.nio.file.Files.createDirectory(root, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        } else {
            java.nio.file.Files.createDirectory(root);
        }
    }

    /**
     * Refuses a home someone else could plant programs in: owned by another user, or writable by
     * everyone (JKIT_HOME=/tmp/jkit-home on a shared machine). Group-writable is fine: with a
     * private group per user, that's the default umask on some distributions.
     */
    public void requireSafe() {
        if (!POSIX || !java.nio.file.Files.isDirectory(root)) {
            return;
        }
        try {
            String owner = java.nio.file.Files.getOwner(root).getName();
            String me = System.getProperty("user.name");
            if (!owner.equals(me)) {
                throw new dev.jkit.model.JkitException(root + " belongs to " + owner + ", not to you (" + me + ")",
                        "use a JKIT_HOME of your own, e.g. ~/.jkit");
            }
            if (java.nio.file.Files.getPosixFilePermissions(root)
                    .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                throw new dev.jkit.model.JkitException(root + " is writable by every user, so anyone could put "
                        + "programs on your PATH", "fix it: chmod o-w " + root);
            }
        } catch (java.io.IOException e) {
            throw new dev.jkit.model.JkitException("cannot check " + root + ": " + e.getMessage());
        }
    }
}
