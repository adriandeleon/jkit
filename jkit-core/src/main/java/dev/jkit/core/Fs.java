package dev.jkit.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class Fs {
    private Fs() { }

    /**
     * Deletes a folder tree without following symlinks or Windows junctions (which Java reports as
     * folders, with isOther set): the link goes, what it points at stays. Missing folders are ignored.
     */
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
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
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
