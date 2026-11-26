package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import dev.jkit.model.ShellQuote;
import dev.jkit.model.Tool;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/** The ~/.jkit layout. */
public record JkitHome(Path root) {

    public static JkitHome fromEnv(Map<String, String> env) {
        String override = env.get("JKIT_HOME");
        if (override != null && !override.isBlank()) {
            Path path = Fs.path(override);
            if (path == null || !path.isAbsolute()) {
                // relative to each folder you cd into: a cloned repo could supply its own "tools"
                throw new JkitException("JKIT_HOME must be an absolute path, not " + override,
                        "e.g. JKIT_HOME=$HOME/.jkit (a quoted ~ is not expanded)");
            }
            return new JkitHome(path);
        }
        // $HOME first, like the shell: it differs from the passwd entry under sudo -E, in some
        // containers and in tests that point HOME somewhere else
        String home = env.get("HOME");
        if (Fs.path(home) == null || !Fs.path(home).isAbsolute() || !Owners.posix()) {
            home = System.getProperty("user.home");
        }
        // no passwd entry (docker run -u 1001) and no HOME: user.home is "?", relative to every folder
        Path base = Fs.path(home);
        if (base == null || !base.isAbsolute()) {
            throw new JkitException("can't tell your home folder (HOME isn't set, and this user has "
                    + "no home in the system's user list)", "set HOME, or JKIT_HOME, to an absolute path");
        }
        return new JkitHome(base.resolve(".jkit"));
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

    /** Held only while global links change or an install folder is moved away: never for a download. */
    public Path linkLockFile() { return root.resolve(".links.lock"); }

    public Path config() { return root.resolve("config.toml"); }

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * Creates the home if needed, readable only by this user: its {@code bin} folders go first on
     * PATH, so nobody else may put anything in it.
     */
    public void create() throws IOException {
        if (Files.isDirectory(root)) {
            // whoever made it, maybe another user since this command started: checked again
            requireSafe();
            return;
        }
        if (root.getParent() != null) {
            Perms.mkdirs(root.getParent());
        }
        try {
            if (POSIX) {
                Files.createDirectory(root, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(root);
            }
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(root)) {
                throw e; // a file of that name; another jkit making the folder at the same moment is fine
            }
        }
        requireSafe(); // whoever made it, and whatever is above it: checked before anything goes in
    }

    /**
     * Refuses a home someone else could plant programs in: the home, or its {@code tools} and
     * {@code global} folders, owned by another user (root is fine) or writable by everyone, or a
     * folder above it where anyone could rename it away and put their own in its place (writable by
     * everyone without /tmp's sticky bit). Group-writable is fine: with a private group per user,
     * that's the default umask on some distributions.
     */
    public void requireSafe() {
        if (!POSIX) {
            return;
        }
        try {
            boolean exists = Files.isDirectory(root);
            if (exists) {
                for (Path dir : List.of(root, root.resolve("tools"), root.resolve("global"))) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    if (!Owners.mineOrRoot(dir)) {
                        throw new JkitException(dir + " belongs to " + Owners.owner(dir) + ", not to you",
                                dir.equals(root) ? "use a JKIT_HOME of your own, e.g. ~/.jkit"
                                        : "fix it: chown -R \"$(id -u)\" " + ShellQuote.quote("bash", root.toString()));
                    }
                    if (Owners.othersWritable(dir)) {
                        throw new JkitException(dir + " is writable by every user, so anyone could put "
                                + "programs on your PATH", "fix it: chmod o-w " + ShellQuote.quote("bash", dir.toString()));
                    }
                }
            }
            // the folders above, as they really are (a symlink on the way counts as where it leads);
            // for a home not made yet, from the nearest folder that exists, so the first run is checked too
            Path start = root;
            while (start != null && !Files.exists(start)) {
                start = start.getParent();
            }
            if (start == null) {
                return;
            }
            Path real = start.toRealPath();
            for (Path up = exists ? real.getParent() : real; up != null; up = up.getParent()) {
                if (Owners.othersCanReplaceEntries(up)) {
                    throw new JkitException(root + " is in " + up + ", which every user can write "
                            + "to, so anyone could replace it", "use a JKIT_HOME elsewhere, e.g. ~/.jkit");
                }
                if (!Owners.mineOrRoot(up)) {
                    throw new JkitException(root + " is in " + up + ", which belongs to "
                            + Owners.owner(up) + ", who could replace it", "use a JKIT_HOME of your own, e.g. ~/.jkit");
                }
            }
        } catch (IOException e) {
            throw new JkitException("cannot check " + root + ": " + e.getMessage());
        }
    }
}
