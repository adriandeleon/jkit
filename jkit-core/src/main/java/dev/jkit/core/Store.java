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
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(marker)) {
                    p.load(in);
                }
                String vendor = p.getProperty("vendor", "");
                Installed installed = new Installed(tool.storeTool(), Version.parse(p.getProperty("version")),
                        vendor.isEmpty() ? null : Vendor.fromFoojay(vendor), child,
                        Boolean.parseBoolean(p.getProperty("fx", "false")));
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

    /** Installs matching a version prefix and (for vendor tools) a vendor, newest first. */
    public List<Installed> find(Tool tool, String versionSpec, Vendor vendor) throws IOException {
        return list(tool).stream()
                .filter(i -> i.version().matches(versionSpec))
                .filter(i -> vendor == null || vendor.equals(i.vendor()))
                .toList();
    }

    public boolean contains(Tool tool, Version version, Vendor vendor, boolean fx) throws IOException {
        return Files.isDirectory(home.tools(tool.storeTool()).resolve(Installed.dirName(version, vendor, fx)));
    }

    static void writeMarker(Path dir, Installed installed, String url) throws IOException {
        Properties p = new Properties();
        p.setProperty("tool", installed.tool().id());
        p.setProperty("version", installed.version().raw());
        p.setProperty("vendor", installed.vendor() == null ? "" : installed.vendor().foojayName());
        p.setProperty("fx", Boolean.toString(installed.fx()));
        p.setProperty("url", url);
        try (var out = Files.newOutputStream(dir.resolve(MARKER))) {
            p.store(out, "written by jkit");
        }
    }
}
