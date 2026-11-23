package dev.jkit.model;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Folders and files jkit makes in its home get explicit permissions, so a umask of 000 can't
 * leave them writable by every user (the home check would then refuse to run).
 */
public final class Perms {
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private Perms() { }

    /** createDirectories, each new folder rwxr-xr-x. */
    public static Path mkdirs(Path dir) throws IOException {
        return POSIX
                ? Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
                : Files.createDirectories(dir);
    }

    /** rw-r--r-- for a new file, or nothing where there are no POSIX permissions. */
    public static FileAttribute<?>[] file() {
        return POSIX
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))}
                : new FileAttribute<?>[0];
    }
}
