package dev.jkit.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The folders under {@code tmp/} that an install or self-update is working in right now, so that
 * Ctrl-C or a SIGTERM can delete them on the way out instead of leaving a partial download behind
 * until the next install or jkit prune.
 */
public final class WorkDirs {
    private static final Set<Path> ACTIVE = ConcurrentHashMap.newKeySet();

    private WorkDirs() { }

    public static void add(Path dir) {
        ACTIVE.add(dir);
    }

    public static void remove(Path dir) {
        ACTIVE.remove(dir);
    }

    /** Deletes every folder still in use, as far as it can; returns the ones it deleted. */
    public static List<Path> deleteAll() {
        List<Path> deleted = new ArrayList<>();
        for (Path dir : List.copyOf(ACTIVE)) {
            try {
                Fs.deleteRecursively(dir);
                deleted.add(dir);
            } catch (IOException | RuntimeException e) {
                // the next install or jkit prune clears what's left
            }
            ACTIVE.remove(dir);
        }
        return deleted;
    }
}
