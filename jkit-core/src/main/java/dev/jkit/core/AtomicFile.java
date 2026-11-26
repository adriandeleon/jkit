package dev.jkit.core;

import dev.jkit.model.Perms;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes a user's file (a shell startup file, config.toml, jkit.toml) all at once: the text goes
 * to a new file in the same folder, which then takes the old one's place in one step. A crash, a
 * full disk or Ctrl-C halfway leaves the old file as it was, never a truncated ~/.bashrc; after a
 * crash, also a {@code .<name>.<digits>.jkit-tmp} next to it, which jkit doctor points out.
 */
public final class AtomicFile {
    /** How many links in a row are followed before giving up (Linux's own limit). */
    private static final int MAX_LINKS = 40;

    private AtomicFile() { }

    /**
     * Writes {@code text} to {@code file} in {@code charset}; the old file's POSIX permissions stay,
     * and a new file gets rw-r--r-- whatever the umask. With {@code followLinks}, a {@code file}
     * that is a symbolic link (a dotfile manager's) stays one: the file it points to gets the text.
     * Without, a link there is replaced by the file and what it pointed to is left alone. A file
     * that isn't writable is refused, as writing it in place would be. Where the new file can't
     * have the old one's owner, or (Windows) can't take its place, the text is written in place.
     * Returns the file written.
     */
    public static Path write(Path file, String text, Charset charset, boolean followLinks) throws IOException {
        boolean windows = File.separatorChar == '\\';
        Path target = followLinks ? resolve(file) : file;
        Path dir = target.toAbsolutePath().getParent();
        byte[] bytes = encode(text, charset);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target);
        if (exists && !Files.isWritable(target)) {
            // as writing it in place would: a read-only file stays as it is
            throw new AccessDeniedException(target.toString());
        }
        Set<PosixFilePermission> perms = null;
        if (Perms.posix()) {
            perms = exists ? Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)
                    : PosixFilePermissions.fromString("rw-r--r--");
        }
        FileAttribute<?>[] owner = Perms.posix()
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", TEMP_SUFFIX, owner);
        } catch (AccessDeniedException e) {
            if (!exists) {
                throw e;
            }
            // a folder only root may add to, holding a file the user may write: in place, as before
            Files.write(target, bytes);
            return target;
        }
        boolean replaced = false;
        try {
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                out.force(true);
            }
            if (perms != null) {
                Files.setPosixFilePermissions(tmp, perms); // not subject to the umask
            }
            if (exists && Perms.posix() && !matchOwner(target, tmp)) {
                // root editing a user's ~/.bashrc, or a file of another group: the new file must not
                // end up root's; where the owner can't be given, in place, as before
                Files.deleteIfExists(tmp);
                Files.write(target, bytes);
                return target;
            }
            replaced = replace(tmp, target, windows);
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (!replaced) {
            Files.write(target, bytes); // Windows kept the old one open (an editor, a virus scanner)
            return target;
        }
        if (Perms.posix()) {
            syncDir(dir);
        }
        return target;
    }

    /** What a temporary file left by a crash ends with, next to the file it was for. */
    public static final String TEMP_SUFFIX = ".jkit-tmp";

    /**
     * Temporary files a write that never finished (a crash, a kill -9) left in {@code dir}:
     * {@code .<name>.<digits>.jkit-tmp}. A write that is running now has one too, briefly.
     */
    public static List<Path> leftovers(Path dir) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, ".*" + TEMP_SUFFIX)) {
            for (Path f : files) {
                if (f.getFileName().toString().matches("\\..+\\.[0-9]+" + Pattern.quote(TEMP_SUFFIX))
                        && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    found.add(f);
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // no such folder, or one jkit can't read: nothing it left there
        }
        found.sort(null);
        return found;
    }

    /**
     * Gives the new file the old one's owner and group when they differ (root writing a user's
     * file); false when that can't be done here.
     */
    private static boolean matchOwner(Path target, Path tmp) {
        try {
            Object uid = Files.getAttribute(target, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Object gid = Files.getAttribute(target, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            if (!uid.equals(Files.getAttribute(tmp, "unix:uid"))) {
                Files.setAttribute(tmp, "unix:uid", uid);
            }
            if (!gid.equals(Files.getAttribute(tmp, "unix:gid"))) {
                Files.setAttribute(tmp, "unix:gid", gid);
            }
            return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException | ClassCastException e) {
            return false;
        }
    }

    /**
     * Moves {@code tmp} over {@code target}. On Windows a file another program has open can't be
     * replaced for a moment: a few tries, then false, so the caller writes in place.
     */
    private static boolean replace(Path tmp, Path target, boolean windows) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (AccessDeniedException e) {
                if (!windows) {
                    throw e;
                }
                if (attempt == 5) {
                    return false;
                }
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** The rename is on disk only once its folder is: best effort, as not every system allows it. */
    private static void syncDir(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            // the file itself is on disk already
        }
    }

    /** The file a link leads to, even one that doesn't exist yet; {@code file} itself when it isn't a link. */
    public static Path resolve(Path file) throws IOException {
        if (!Files.isSymbolicLink(file)) {
            return file;
        }
        if (Files.exists(file)) {
            return file.toRealPath();
        }
        Path p = file.toAbsolutePath(); // a link to a file not there yet: follow it by hand
        for (int i = 0; i < MAX_LINKS && Files.isSymbolicLink(p); i++) {
            p = p.getParent().resolve(Files.readSymbolicLink(p));
        }
        if (Files.isSymbolicLink(p)) {
            throw new IOException(file + ": too many levels of symbolic links");
        }
        return p;
    }

    /** Like Files.writeString: text the charset can't hold is an error, not a silent '?'. */
    private static byte[] encode(String text, Charset charset) throws IOException {
        ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }
}
