package dev.jkit.core;

import dev.jkit.model.Tool;
import java.nio.file.Path;
import java.util.Map;

/** The ~/.jkit layout. */
public record JkitHome(Path root) {

    public static JkitHome fromEnv(Map<String, String> env) {
        String override = env.get("JKIT_HOME");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (!path.isAbsolute()) {
                // relative to each folder you cd into: a cloned repo could supply its own "tools"
                throw new dev.jkit.model.JkitException("JKIT_HOME must be an absolute path, not " + override,
                        "e.g. JKIT_HOME=$HOME/.jkit (a quoted ~ is not expanded)");
            }
            return new JkitHome(path);
        }
        // $HOME first, like the shell: it differs from the passwd entry under sudo -E, in some
        // containers and in tests that point HOME somewhere else
        String home = env.get("HOME");
        if (home == null || home.isBlank() || !Path.of(home).isAbsolute() || !Owners.posix()) {
            home = System.getProperty("user.home");
        }
        return new JkitHome(Path.of(home, ".jkit"));
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
        try {
            if (POSIX) {
                java.nio.file.Files.createDirectory(root, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            } else {
                java.nio.file.Files.createDirectory(root);
            }
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (!java.nio.file.Files.isDirectory(root)) {
                throw e; // a file of that name; another jkit making the folder at the same moment is fine
            }
        }
    }

    /**
     * Refuses a home someone else could plant programs in: the home, or its {@code tools} and
     * {@code global} folders, owned by another user (root is fine) or writable by everyone, or a
     * folder above it where anyone could rename it away and put their own in its place (writable by
     * everyone without /tmp's sticky bit). Group-writable is fine: with a private group per user,
     * that's the default umask on some distributions.
     */
    public void requireSafe() {
        if (!POSIX || !java.nio.file.Files.isDirectory(root)) {
            return;
        }
        try {
            for (Path dir : java.util.List.of(root, root.resolve("tools"), root.resolve("global"))) {
                if (!java.nio.file.Files.isDirectory(dir)) {
                    continue;
                }
                if (!Owners.mineOrRoot(dir)) {
                    throw new dev.jkit.model.JkitException(dir + " belongs to " + Owners.owner(dir) + ", not to you",
                            dir.equals(root) ? "use a JKIT_HOME of your own, e.g. ~/.jkit"
                                    : "fix it: chown -R \"$(id -u)\" " + root);
                }
                if (Owners.othersWritable(dir)) {
                    throw new dev.jkit.model.JkitException(dir + " is writable by every user, so anyone could put "
                            + "programs on your PATH", "fix it: chmod o-w " + dir);
                }
            }
            for (Path up = root.getParent(); up != null; up = up.getParent()) {
                if (Owners.othersCanReplaceEntries(up)) {
                    throw new dev.jkit.model.JkitException(root + " is in " + up + ", which every user can write "
                            + "to, so anyone could replace it", "use a JKIT_HOME elsewhere, e.g. ~/.jkit");
                }
            }
        } catch (java.io.IOException e) {
            throw new dev.jkit.model.JkitException("cannot check " + root + ": " + e.getMessage());
        }
    }
}
