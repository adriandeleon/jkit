package dev.jkit.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class Fs {
    private Fs() { }

    /**
     * A path from an environment variable, or null when it is unset, blank or not a valid path.
     * One pair of surrounding double quotes is dropped: on Windows {@code JAVA_HOME="C:\\Program Files\\..."}
     * keeps the quotes, and {@code "} isn't allowed in a Windows path.
     */
    public static Path path(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            return Path.of(v);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Whether two paths name the same folder: equal as written, or the same file on disk, links
     * followed (the global link and its target are the same install). Windows reads a junction back
     * with long names (runneradmin) where jkit was given short ones (RUNNER~1), so comparing the
     * text alone says a global link points elsewhere when it doesn't.
     */
    public static boolean same(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())) {
            return true;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies a folder tree, links as links, keeping file permissions. {@code to} must not exist. */
    public static void copyTree(Path from, Path to) throws IOException {
        try (var all = Files.walk(from)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isSymbolicLink(p)) {
                    Files.createSymbolicLink(target, Files.readSymbolicLink(p));
                } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES,
                            LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    /**
     * Deletes a folder tree without following symlinks or Windows junctions (which Java reports as
     * folders, with isOther set): the link goes, what it points at stays. Missing folders are ignored.
     */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (attrs.isOther() && !d.equals(dir)) {
                    Files.delete(d); // a junction: remove the link only
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
