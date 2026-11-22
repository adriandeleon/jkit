package dev.jkit.core;

import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
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
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // stable aliases are links
                }
                Path marker = child.resolve(MARKER);
                if (!Files.isRegularFile(marker)) {
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
        out.sort(Comparator.comparing(Installed::version).reversed().thenComparing(Installed::fx));
        return out;
    }

    private static Installed read(Tool tool, Path dir, Path marker) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            p.load(in);
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
        return Files.isRegularFile(home.tools(tool.storeTool()).resolve(Installed.dirName(version, vendor, fx)).resolve(MARKER));
    }

    /**
     * Holds jkit's install lock until closed: one process at a time changes tools/. Blocks while
     * another jkit holds it.
     */
    public AutoCloseable lock() throws IOException {
        Files.createDirectories(home.root());
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(home.lockFile(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock;
        try {
            lock = channel.lock();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return () -> {
            try (channel) {
                lock.release();
            }
        };
    }

    /** Whether the install was checked against a strong checksum; false for installs made before jkit recorded it. */
    static boolean verified(Path dir) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve(MARKER))) {
            p.load(in);
        } catch (IOException e) {
            return false;
        }
        return Boolean.parseBoolean(p.getProperty("verified", "false"));
    }

    static void writeMarker(Path dir, Installed installed, String url, boolean verified) throws IOException {
        Properties p = new Properties();
        p.setProperty("tool", installed.tool().id());
        p.setProperty("version", installed.version().raw());
        p.setProperty("vendor", installed.vendor() == null ? "" : installed.vendor().foojayName());
        p.setProperty("fx", Boolean.toString(installed.fx()));
        p.setProperty("url", url);
        p.setProperty("verified", Boolean.toString(verified));
        try (var out = Files.newOutputStream(dir.resolve(MARKER))) {
            p.store(out, "written by jkit");
        }
    }
}
