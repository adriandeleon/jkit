package dev.jkit.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes a user's file (a shell startup file, config.toml, jkit.toml) all at once: the text goes
 * to a new file in the same folder, which then takes the old one's place in one step. A crash, a
 * full disk or Ctrl-C halfway leaves the old file as it was, never a truncated ~/.bashrc.
 */
public final class AtomicFile {
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    /** How many links in a row are followed before giving up (Linux's own limit). */
    private static final int MAX_LINKS = 40;

    private AtomicFile() { }

    /**
     * Writes {@code text} to {@code file} in {@code charset}; the old file's POSIX permissions stay,
     * and a new file gets rw-r--r-- whatever the umask. With {@code followLinks}, a {@code file}
     * that is a symbolic link (a dotfile manager's) stays one: the file it points to gets the text.
     * Without, a link there is replaced by the file and what it pointed to is left alone. Returns
     * the file written.
     */
    public static Path write(Path file, String text, Charset charset, boolean followLinks) throws IOException {
        Path target = followLinks ? resolve(file) : file;
        Path dir = target.toAbsolutePath().getParent();
        byte[] bytes = encode(text, charset);
        Set<PosixFilePermission> perms = null;
        if (POSIX) {
            perms = Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)
                    ? Files.getPosixFilePermissions(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    : PosixFilePermissions.fromString("rw-r--r--");
        }
        FileAttribute<?>[] owner = POSIX
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".jkit-tmp", owner);
        } catch (java.nio.file.AccessDeniedException e) {
            if (Files.isSymbolicLink(target) || !Files.isWritable(target)) {
                throw e;
            }
            // a folder only root may add to, holding a file the user may write: in place, as before
            Files.write(target, bytes);
            return target;
        }
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
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /** The file a link leads to, even one that doesn't exist yet; {@code file} itself when it isn't a link. */
    static Path resolve(Path file) throws IOException {
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
