package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import dev.jkit.model.Visible;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Unpacks tar.gz and zip archives keeping Unix permissions and symlinks, which java.util.zip
 * drops. Entries that would land outside the target folder are refused, and so are symlinks that
 * point outside it and entries written through a symlink an earlier entry made.
 */
public final class Archives {
    private Archives() { }

    /** Unpacked size and entry count limits: the biggest JDKs unpack to under 1 GB and 1,000 files. */
    static final long MAX_UNPACKED = 3L << 30;
    static final int MAX_ENTRIES = 50_000;

    /** What an archive may still unpack. */
    private static final class Budget {
        final int entryLimit;
        long bytes = MAX_UNPACKED;
        int entries;

        Budget(int entryLimit) {
            this.entryLimit = entryLimit;
            this.entries = entryLimit;
        }

        void entry() throws IOException {
            if (--entries < 0) {
                throw unsafe("more than " + entryLimit + " entries");
            }
            if (WorkDirs.interrupted()) {
                throw new InterruptedIOException("interrupted"); // Ctrl-C is deleting the folder
            }
        }

        /** Copies {@code in} to a new file {@code out}, counting against the size limit. */
        void copy(InputStream in, Path out) throws IOException {
            try (var os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    bytes -= n;
                    if (bytes < 0) {
                        throw unsafe("it unpacks to more than " + (MAX_UNPACKED >> 30) + " GB");
                    }
                    os.write(buffer, 0, n);
                }
            }
        }
    }

    /** A download that is well-formed but would escape its folder or fill the disk: not a jkit bug. */
    static JkitException unsafe(String detail) {
        return new JkitException("the downloaded archive is unsafe to unpack (" + detail
                + "); nothing was installed", "the source or a mirror served a broken or hostile archive; "
                + "try another version or vendor, and report it to the source");
    }

    public static void extract(Path archive, Path target, String archiveType) throws IOException {
        extract(archive, target, archiveType, MAX_ENTRIES);
    }

    /** {@link #extract(Path, Path, String)} with another entry limit: tests don't build 50,000 entries. */
    static void extract(Path archive, Path target, String archiveType, int entryLimit) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        switch (archiveType) {
            case "tar.gz", "tgz" -> {
                ArchiveScan.tarGz(archive, entryLimit, MAX_UNPACKED);
                extractTarGz(archive, root, new Budget(entryLimit));
            }
            case "zip" -> {
                ArchiveScan.zip(archive, entryLimit);
                extractZip(archive, root, new Budget(entryLimit));
            }
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
        closeToOthers(root);
    }

    /**
     * Takes away write access for the group and other users from everything unpacked, folders
     * included: the archive's modes are already masked, but folders follow the umask, which may be
     * 002 or 000.
     */
    private static void closeToOthers(Path root) throws IOException {
        if (!Perms.posix()) {
            return;
        }
        try (var all = Files.walk(root)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
                if (perms.remove(PosixFilePermission.OTHERS_WRITE) | perms.remove(PosixFilePermission.GROUP_WRITE)) {
                    Files.setPosixFilePermissions(p, perms);
                }
            }
        }
    }

    private static void extractTarGz(Path archive, Path target, Budget budget) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             // every gzip member, as ArchiveScan reads it (bgzip and pigz write several)
             TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(raw, true))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                budget.entry();
                Path out = safe(target, entry.getName());
                // ArchiveScan refuses these; checked again in case the two ever read an archive differently
                if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO() || entry.isSparse()) {
                    throw unsafe("it holds a device, pipe or sparse file: " + entry.getName());
                }
                if (entry.isDirectory() && entry.getSize() > 0) {
                    throw unsafe("a folder entry in it carries data");
                }
                if (entry.isDirectory()) {
                    createDirectories(target, out);
                } else if (entry.isSymbolicLink()) {
                    symlink(target, out, entry.getLinkName(), entry.getName());
                } else if (entry.isLink()) {
                    Path source = safe(target, entry.getLinkName());
                    noLinksBetween(target, source, entry.getName());
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw unsafe("archive hard link points at no earlier file: " + entry.getName());
                    }
                    createDirectories(target, out.getParent());
                    budget.bytes -= Files.size(source);
                    if (budget.bytes < 0) {
                        throw unsafe("it unpacks to more than " + (MAX_UNPACKED >> 30) + " GB");
                    }
                    Files.copy(source, out, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                } else {
                    createDirectories(target, out.getParent());
                    Files.deleteIfExists(out);
                    budget.copy(tar, out);
                    setMode(out, entry.getMode());
                }
            }
        }
    }

    private static void extractZip(Path archive, Path target, Budget budget) throws IOException {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                budget.entry();
                Path out = safe(target, entry.getName());
                if (entry.isDirectory()) {
                    createDirectories(target, out);
                } else if (entry.isUnixSymlink()) {
                    symlink(target, out, linkTarget(zip, entry), entry.getName());
                } else {
                    createDirectories(target, out.getParent());
                    Files.deleteIfExists(out);
                    try (InputStream in = zip.getInputStream(entry)) {
                        budget.copy(in, out);
                    }
                    setMode(out, entry.getUnixMode());
                }
            }
        }
    }

    /**
     * A link whose target, resolved from where it sits, stays inside the archive's top folder (only
     * that folder is installed), or inside the target folder for a link at the top. {@code ..} may
     * only lead the target: after a name it would climb out of whatever that name turns out to be,
     * and an earlier or later entry can make that name a symlink, so checking the text alone isn't
     * enough ({@code a/b/../..} is inside on paper, outside if {@code a/b} links to {@code .}).
     */
    private static void symlink(Path target, Path out, String linkName, String entryName) throws IOException {
        if (linkName == null || linkName.isEmpty()) {
            throw unsafe("archive symlink without a target: " + entryName);
        }
        Path link = path(linkName, entryName);
        boolean named = false;
        boolean climbsAfterName = false;
        for (Path part : link) {
            String p = part.toString();
            if (p.equals("..")) {
                climbsAfterName |= named;
            } else if (!p.equals(".") && !p.isEmpty()) {
                named = true;
            }
        }
        Path relative = target.relativize(out);
        Path inside = relative.getNameCount() > 1 ? target.resolve(relative.getName(0)) : target;
        if (link.isAbsolute() || link.getRoot() != null || climbsAfterName
                || !out.getParent().resolve(link).normalize().startsWith(inside)) {
            throw unsafe("archive symlink points outside the target folder: " + entryName + " -> " + linkName);
        }
        createDirectories(target, out.getParent());
        try {
            Files.deleteIfExists(out);
        } catch (DirectoryNotEmptyException e) {
            throw unsafe("a link replaces a folder already unpacked: " + entryName);
        }
        Files.createSymbolicLink(out, link);
    }

    /** A zip symlink's target, read only up to a path's length: ZipFile would read any size whole. */
    private static String linkTarget(ZipFile zip, ZipArchiveEntry entry) throws IOException {
        if (entry.getSize() > ArchiveScan.MAX_NAME) {
            throw unsafe("a symlink entry holds " + entry.getSize() + " bytes: " + entry.getName());
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(ArchiveScan.MAX_NAME + 1);
            if (bytes.length > ArchiveScan.MAX_NAME) {
                throw unsafe("a symlink entry is too long: " + entry.getName());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * createDirectories that refuses to go through a symlink an earlier entry made, and makes the
     * folders below {@code target} one at a time, each in one that exists: should Ctrl-C delete the
     * work folder halfway, the next entry fails instead of making it again, to be left behind.
     */
    private static void createDirectories(Path target, Path dir) throws IOException {
        noLinksBetween(target, dir, dir.toString());
        Path p = target;
        for (Path name : target.relativize(dir)) {
            p = p.resolve(name.toString());
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(p);
                } catch (FileAlreadyExistsException e) {
                    if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        throw e;
                    }
                }
            }
        }
    }

    /** Refuses a path below {@code target} with a symlink anywhere on the way (the path itself included). */
    private static void noLinksBetween(Path target, Path path, String entryName) throws IOException {
        for (Path p = path; p != null && !p.equals(target); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw unsafe("archive entry goes through a symlink: " + entryName);
            }
        }
    }

    private static Path safe(Path target, String name) throws IOException {
        Path out = target.resolve(path(name, name)).normalize();
        if (!out.startsWith(target)) {
            throw unsafe("archive entry escapes the target folder: " + name);
        }
        return out;
    }

    /**
     * An entry name or link target as a path. A name this file system can't hold (a NUL byte, or
     * on Windows a {@code :} or {@code *}) is the archive's fault, not a jkit bug.
     */
    private static Path path(String name, String entryName) {
        try {
            return Path.of(name);
        } catch (InvalidPathException e) {
            throw unsafe("an entry name this system can't use: " + Visible.of(entryName));
        }
    }

    private static void setMode(Path file, int mode) throws IOException {
        if (!Perms.posix() || mode == 0) {
            return;
        }
        // never writable by anyone but the owner, whatever the archive says: these run from PATH
        mode &= 0755;
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
        };
        for (int bit = 0; bit < order.length; bit++) {
            if ((mode & (1 << bit)) != 0) {
                perms.add(order[bit]);
            }
        }
        Files.setPosixFilePermissions(file, perms);
    }
}
