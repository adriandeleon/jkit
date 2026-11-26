package dev.jkit.core;

import dev.jkit.model.Perms;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/** Reads what is installed under tools/. Each install carries a jkit-install.properties file. */
public final class Store {
    public static final String MARKER = "jkit-install.properties";

    private final JkitHome home;

    public Store(JkitHome home) {
        this.home = home;
    }

    /** Installs usable as {@code tool}, newest first. GraalVM = GraalVM-based JDK installs. */
    public List<Installed> list(Tool tool) throws IOException {
        Path dir = home.tools(tool.storeTool());
        List<Installed> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (child.getFileName().toString().startsWith(".") // an install still being copied in (.name.partial)
                        || Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // stable aliases are links
                }
                Path marker = child.resolve(MARKER);
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Installed installed = read(tool, child, marker);
                if (installed == null) {
                    continue; // a damaged marker; doctor reports the folder as a leftover
                }
                if (tool == Tool.GRAALVM && (installed.vendor() == null || !installed.vendor().isGraalVmBased())) {
                    continue;
                }
                out.add(installed);
            }
        }
        // newest first; a plain build before a JavaFX build of the same version
        // vendor last, so equal versions come in the same order on every machine (not directory order)
        out.sort(Comparator.comparing(Installed::version).reversed().thenComparing(Installed::fx)
                .thenComparing(i -> i.vendor() == null ? "" : i.vendor().name()));
        return out;
    }

    private static Installed read(Tool tool, Path dir, Path marker) {
        try {
            Properties p = load(marker);
            String version = p.getProperty("version", "").trim();
            if (version.isEmpty()) {
                return null;
            }
            String vendor = p.getProperty("vendor", "").trim();
            return new Installed(tool.storeTool(), Version.parse(version),
                    vendor.isEmpty() ? null : Vendor.fromFoojay(vendor), dir,
                    Boolean.parseBoolean(p.getProperty("fx", "false")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Installs matching a version prefix and (for vendor tools) a vendor, newest first. */
    public List<Installed> find(Tool tool, String versionSpec, Vendor vendor) throws IOException {
        return list(tool).stream()
                .filter(i -> i.version().matches(versionSpec))
                .filter(i -> vendor == null || vendor.equals(i.vendor()))
                .toList();
    }

    /** True when that build is installed: its folder exists and carries jkit's marker. */
    public boolean contains(Tool tool, Version version, Vendor vendor, boolean fx) throws IOException {
        return Files.isRegularFile(home.tools(tool.storeTool()).resolve(Installed.dirName(version, vendor, fx)).resolve(MARKER),
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Holds jkit's install lock until closed: one process at a time changes tools/. Blocks while
     * another jkit holds it.
     */
    public Lock lock() throws IOException {
        return lock(home.lockFile(), "waiting for another jkit to finish (an install can take a few minutes)…");
    }

    /**
     * Holds the link lock until closed: global links change, and install folders are moved away,
     * one process at a time. Short-lived, so a jkit use --global doesn't wait for a download. Taken
     * after {@link #lock()} when both are needed, never before.
     */
    public Lock linkLock() throws IOException {
        return lock(home.linkLockFile(), "waiting for another jkit to finish changing links…");
    }

    /** A lock held until closed. */
    public interface Lock extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    /** Each lock file's turn within this process: see {@link #lock(Path, String)}. */
    private static final Map<Path, ReentrantLock> TURNS = new ConcurrentHashMap<>();

    /**
     * Holds the OS lock on {@code file} until closed, saying {@code waiting} on stderr when another
     * jkit holds it instead of hanging silently. Within this process the file is opened and locked
     * once: on Unix, closing any channel to a file lets go of every lock the process has on it, so
     * a second channel (Installer's and the commands' lock on the same file) would release the
     * first one's lock when closed. Taken again by the same thread, it is held until both close;
     * another thread waits its turn.
     */
    Lock lock(Path file, String waiting) throws IOException {
        home.create();
        ReentrantLock turn = TURNS.computeIfAbsent(file.toAbsolutePath().normalize(), f -> new ReentrantLock());
        if (!turn.tryLock()) {
            System.err.println(waiting);
            turn.lock();
        }
        if (turn.getHoldCount() > 1) {
            return turn::unlock; // this thread holds the file's lock already
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), Perms.file());
            FileLock lock = channel.tryLock();
            if (lock == null) {
                System.err.println(waiting);
                lock = channel.lock();
            }
            FileChannel open = channel;
            FileLock held = lock;
            return () -> {
                try (open) {
                    held.release();
                } finally {
                    turn.unlock();
                }
            };
        } catch (IOException | RuntimeException e) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } finally {
                turn.unlock();
            }
            throw e;
        }
    }

    /** Whose OpenPGP signature the download was checked against, or null: none, or installed before jkit checked them. */
    public static String signedBy(Path dir) {
        try {
            String who = load(dir.resolve(MARKER)).getProperty("signedBy");
            return who == null || !who.matches("[A-Za-z][A-Za-z ]{0,40}") ? null : who;
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether the install was checked against a strong checksum; false for installs made before jkit recorded it. */
    public static boolean verified(Path dir) {
        Properties p;
        try {
            p = load(dir.resolve(MARKER));
        } catch (IOException e) {
            return false;
        }
        return Boolean.parseBoolean(p.getProperty("verified", "false"));
    }

    static void writeMarker(Path dir, Installed installed, String url, boolean verified) throws IOException {
        writeMarker(dir, installed, url, verified, null);
    }

    /** @param signedBy whose signature was checked (Adoptium, Apache Maven, ...), or null */
    static void writeMarker(Path dir, Installed installed, String url, boolean verified, String signedBy)
            throws IOException {
        Properties p = new Properties();
        p.setProperty("tool", installed.tool().id());
        p.setProperty("version", installed.version().raw());
        p.setProperty("vendor", installed.vendor() == null ? "" : installed.vendor().foojayName());
        p.setProperty("fx", Boolean.toString(installed.fx()));
        p.setProperty("url", url);
        p.setProperty("verified", Boolean.toString(verified));
        if (signedBy != null) {
            p.setProperty("signedBy", signedBy);
        }
        Path marker = dir.resolve(MARKER);
        // never through a link: an archive could ship one at this name pointing at a file of its own
        Files.deleteIfExists(marker);
        try (var out = Channels.newOutputStream(Files.newByteChannel(marker,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                Perms.file()))) {
            p.store(out, "written by jkit");
        }
    }

    /** The largest marker jkit reads; its own are a few hundred bytes. */
    static final long MAX_MARKER = 64 * 1024;

    /** Reads a marker: a regular file of its own, not a link, and small. */
    static Properties load(Path marker) throws IOException {
        var attrs = Files.readAttributes(marker, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.size() > MAX_MARKER) {
            throw new IOException(marker + " is not jkit's install record");
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            p.load(in);
        }
        return p;
    }
}
