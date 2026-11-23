package dev.jkit.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Environment maps. On Windows variable names ignore case, and the system names PATH "Path", but
 * {@link System#getenv()} is an ordinary case-sensitive map: every lookup of "PATH" in it misses.
 * jkit reads the environment through {@link #system()}, which ignores case on Windows, and copies
 * it with {@link #copy(Map)}, which keeps that.
 */
public final class Envs {
    private Envs() {
    }

    /** The process environment; its names ignore case on Windows. */
    public static Map<String, String> system() {
        return of(System.getenv(), Platform.current().isWindows());
    }

    /** {@code env}, as a map whose names ignore case when {@code windows}. */
    public static Map<String, String> of(Map<String, String> env, boolean windows) {
        if (!windows) {
            return env;
        }
        TreeMap<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(env);
        return Collections.unmodifiableSortedMap(map);
    }

    /** A modifiable copy of {@code env} that ignores case in names when {@code env} does. */
    public static Map<String, String> copy(Map<String, String> env) {
        if (env instanceof SortedMap<String, String> sorted && sorted.comparator() != null) {
            TreeMap<String, String> map = new TreeMap<>(sorted.comparator());
            map.putAll(env);
            return map;
        }
        return new HashMap<>(env);
    }
}
