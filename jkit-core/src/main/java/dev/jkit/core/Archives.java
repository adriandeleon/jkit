package dev.jkit.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * drops. Entries that would land outside the target folder are refused.
 */
public final class Archives {
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private Archives() { }

    public static void extract(Path archive, Path target, String archiveType) throws IOException {
        Files.createDirectories(target);
        switch (archiveType) {
            case "tar.gz", "tgz" -> extractTarGz(archive, target);
            case "zip" -> extractZip(archive, target);
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
    }

    private static void extractTarGz(Path archive, Path target) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path out = safe(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else if (entry.isSymbolicLink()) {
                    Files.createDirectories(out.getParent());
                    Files.createSymbolicLink(out, Path.of(entry.getLinkName()));
                } else if (entry.isLink()) {
                    Files.createDirectories(out.getParent());
                    Files.copy(safe(target, entry.getLinkName()), out, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
                    setMode(out, entry.getMode());
                }
            }
        }
    }

    private static void extractZip(Path archive, Path target) throws IOException {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                Path out = safe(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else if (entry.isUnixSymlink()) {
                    Files.createDirectories(out.getParent());
                    Files.createSymbolicLink(out, Path.of(zip.getUnixSymlink(entry)));
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    setMode(out, entry.getUnixMode());
                }
            }
        }
    }

    private static Path safe(Path target, String name) throws IOException {
        Path out = target.resolve(name).normalize();
        if (!out.startsWith(target)) {
            throw new IOException("archive entry escapes the target folder: " + name);
        }
        return out;
    }

    private static void setMode(Path file, int mode) throws IOException {
        if (!POSIX || mode == 0) {
            return;
        }
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
