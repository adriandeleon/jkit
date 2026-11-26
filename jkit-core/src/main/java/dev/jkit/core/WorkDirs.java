package dev.jkit.core;

import dev.jkit.model.Perms;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The folders under {@code tmp/} that an install or self-update is working in right now, so that
 * Ctrl-C or a SIGTERM can delete them on the way out instead of leaving a partial download behind
 * until the next install or jkit prune.
 * <p>
 * An install downloads without holding the install lock, so another jkit may be clearing
 * leftovers meanwhile: a folder made by {@link #create} carries a {@value #LOCK} file this process
 * holds an OS lock on until the folder is deleted, and {@link #inUse} tells such a folder from one
 * a jkit that is gone left behind. The OS drops the lock when the process ends, however it ends.
 */
public final class WorkDirs {
    /** The file inside a work folder whose lock says a running jkit is using it. */
    static final String LOCK = ".lock";

    /** Each folder in use, with the lock on its {@value #LOCK} file when {@link #create} made it. */
    private static final Map<Path, Optional<FileChannel>> ACTIVE = new ConcurrentHashMap<>();

    private WorkDirs() { }

    /** A folder another lock already keeps others from deleting (self-update holds the install lock). */
    public static void add(Path dir) {
        ACTIVE.put(key(dir), Optional.empty());
    }

    /**
     * A new folder {@code tmp/<prefix>...}, held by this process until {@link #remove}: other
     * jkits' {@link #inUse} says so. Where the file system has no locks, the folder is made all
     * the same, and counts as in use until it is deleted.
     */
    public static Path create(Path tmp, String prefix) throws IOException {
        Path dir = Files.createTempDirectory(tmp, prefix);
        FileChannel channel = FileChannel.open(dir.resolve(LOCK),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), Perms.file());
        try {
            channel.tryLock(); // nobody else knows the folder yet: null only where locks aren't kept
        } catch (IOException e) {
            // no locks on this file system: the lock file alone keeps it
        }
        ACTIVE.put(key(dir), Optional.of(channel));
        return dir;
    }

    /** The folder is done with (deleted, or about to be by this process): lets go of its lock. */
    public static void remove(Path dir) {
        Optional<FileChannel> channel = ACTIVE.remove(key(dir));
        if (channel != null) { // null once Ctrl-C's deleteAll took it
            channel.ifPresent(WorkDirs::close);
        }
    }

    /**
     * Whether a running jkit is working in {@code dir}: this one, or another that holds the lock
     * on its {@value #LOCK} file. A folder without that file is a leftover from an older jkit or
     * from before {@link #create} finished, which happens under the install lock.
     */
    public static boolean inUse(Path dir) {
        if (ACTIVE.containsKey(key(dir))) {
            // never opened here: on Unix, closing any channel to the file would drop this process's lock
            return true;
        }
        Path lock = dir.resolve(LOCK);
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) {
                return true;
            }
            held.release();
            return false;
        } catch (OverlappingFileLockException | IOException e) {
            return true; // can't tell: leave it, doctor reports it
        }
    }

    /** Deletes every folder still in use, as far as it can; returns the ones it deleted. */
    public static List<Path> deleteAll() {
        List<Path> deleted = new ArrayList<>();
        for (Path dir : List.copyOf(ACTIVE.keySet())) {
            Optional<FileChannel> channel = ACTIVE.remove(dir);
            if (channel != null) {
                channel.ifPresent(WorkDirs::close); // first: Windows doesn't delete an open file
            }
            try {
                Fs.deleteRecursively(dir);
                deleted.add(dir);
            } catch (IOException | RuntimeException e) {
                // the next install or jkit prune clears what's left
            }
        }
        return deleted;
    }

    private static Path key(Path dir) {
        return dir.toAbsolutePath().normalize();
    }

    private static void close(FileChannel channel) {
        try {
            channel.close(); // releases its lock
        } catch (IOException e) {
            // the lock goes with the process anyway
        }
    }
}
