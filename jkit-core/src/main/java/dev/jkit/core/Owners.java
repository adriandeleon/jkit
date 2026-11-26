package dev.jkit.core;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

/**
 * Who owns a file, by number: a container user with no passwd entry ({@code docker run -u 1001},
 * OpenShift) has no name ({@code user.name} is {@code ?}), so comparing names would refuse every
 * folder. Root may own things too: a Dockerfile installs as root, then runs as another user.
 */
public final class Owners {
    private static final boolean UNIX_VIEW =
            FileSystems.getDefault().supportedFileAttributeViews().contains("unix");
    private static Integer myUid;

    private Owners() { }

    /**
     * This process's uid, or -1 where it can't be read: on Linux {@code /proc/self} belongs to it;
     * elsewhere a file the process creates does.
     */
    static synchronized int myUid() {
        if (myUid == null) {
            myUid = -1;
            if (UNIX_VIEW) {
                try {
                    myUid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
                } catch (IOException | RuntimeException e) {
                    // no /proc (macOS): compare names, which works for every user with a passwd entry.
                    // Creating a file to read its owner would cost a write on every cd.
                }
            }
        }
        return myUid;
    }

    /** Owned by this user (by uid where possible, else by name). Follows links unless told otherwise. */
    public static boolean mine(Path path, LinkOption... options) throws IOException {
        int me = myUid();
        if (me >= 0) {
            return (Integer) Files.getAttribute(path, "unix:uid", options) == me;
        }
        return Files.getOwner(path, options).getName().equals(System.getProperty("user.name"));
    }

    /** Owned by this user or by root. */
    public static boolean mineOrRoot(Path path, LinkOption... options) throws IOException {
        if (UNIX_VIEW && myUid() >= 0) {
            int uid = (Integer) Files.getAttribute(path, "unix:uid", options);
            return uid == 0 || uid == myUid();
        }
        return mine(path, options) || Files.getOwner(path, options).getName().equals("root");
    }

    /** The owner for a message: name, or uid when it has none. */
    public static String owner(Path path) {
        try {
            return Files.getOwner(path).getName();
        } catch (IOException | RuntimeException e) {
            return "another user";
        }
    }

    /** Writable by every user. Follows links. */
    public static boolean othersWritable(Path path) throws IOException {
        return Files.getPosixFilePermissions(path).contains(PosixFilePermission.OTHERS_WRITE);
    }

    /**
     * Others could rename or replace entries in {@code dir}: it is writable by every user without the
     * sticky bit that {@code /tmp} has. Where the sticky bit can't be read, any others-writable folder counts.
     */
    public static boolean othersCanReplaceEntries(Path dir) throws IOException {
        if (!othersWritable(dir)) {
            return false;
        }
        if (UNIX_VIEW) {
            int mode = (Integer) Files.getAttribute(dir, "unix:mode");
            return (mode & 01000) == 0;
        }
        return true;
    }
}
