package dev.jkit.core;

import dev.jkit.model.Perms;
import java.io.IOException;
import java.io.InterruptedIOException;
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

    /** Folders whose install is in tools/ already: deleted on Ctrl-C all the same, but not reported. */
    private static final Set<Path> DONE = ConcurrentHashMap.newKeySet();

    /** Held by {@link #moveIn} and {@link #deleteAll}, so an install never moves while Ctrl-C deletes. */
    private static final Object MOVING = new Object();

    private static volatile boolean interrupted;

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
        DONE.remove(key(dir));
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

    /**
     * Ctrl-C or a signal is ending jkit, from its handler, before {@link #deleteAll}: from then on
     * no install moves into tools/ ({@link #moveIn}), and jkit's main thread leaves ending the
     * process to the handler ({@link #interrupted}).
     */
    public static void interrupt() {
        interrupted = true;
    }

    /** Whether {@link #interrupt} was called: jkit is being ended by a signal. */
    public static boolean interrupted() {
        return interrupted;
    }

    /** An install's move into tools/: a rename, quick. */
    @FunctionalInterface
    public interface Move {
        void run() throws IOException;
    }

    /**
     * Runs {@code move}, which moves an install into tools/ from {@code moved} (a folder in a work
     * folder, or a copy next to tools/); then the folders they are in hold nothing a Ctrl-C needs
     * to report. Not once jkit is being interrupted, and never while {@link #deleteAll} runs:
     * deleting the folder halfway through a move would leave a partial install with its marker in
     * tools/, after saying nothing was installed.
     */
    public static void moveIn(Move move, Path... moved) throws IOException {
        synchronized (MOVING) {
            if (interrupted) {
                throw new InterruptedIOException("interrupted");
            }
            move.run();
            for (Path from : moved) {
                for (Path dir : ACTIVE.keySet()) {
                    if (key(from).startsWith(dir)) {
                        DONE.add(dir);
                    }
                }
            }
        }
    }

    /**
     * Deletes every folder still in use, as far as it can, after any move into tools/ under way;
     * returns the ones it deleted, not counting those whose install was moved into tools/.
     */
    public static List<Path> deleteAll() {
        synchronized (MOVING) {
            List<Path> deleted = new ArrayList<>();
            for (Path dir : List.copyOf(ACTIVE.keySet())) {
                Optional<FileChannel> channel = ACTIVE.remove(dir);
                if (channel != null) {
                    channel.ifPresent(WorkDirs::close); // first: Windows doesn't delete an open file
                }
                if (delete(dir) && !DONE.remove(dir)) {
                    deleted.add(dir);
                }
            }
            return deleted;
        }
    }

    /**
     * Deletes {@code dir}, trying again for a moment: the install may still be unpacking into a
     * part of it not deleted yet, until its next file finds its folder gone (or it sees it is
     * {@link #interrupted}), and a folder that gains a file while it is deleted isn't empty.
     */
    private static boolean delete(Path dir) {
        for (int attempt = 1; ; attempt++) {
            try {
                Fs.deleteRecursively(dir);
                return true;
            } catch (IOException | RuntimeException e) {
                if (attempt >= 20) {
                    return false; // the next install or jkit prune clears what's left
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
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
