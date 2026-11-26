package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.Store;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Fake installs for the command tests: a folder under tools/ with the install record jkit itself writes. */
final class TestInstalls {
    private TestInstalls() { }

    /**
     * {@code home/tools/<tool>/<dir>}, recorded as an install of what {@code properties} names:
     * {@code version=…}, {@code vendor=…} (foojay's name, empty for none) and optionally {@code fx=true}.
     */
    static Path install(Path home, String tool, String dir, String properties) throws IOException {
        Path path = Files.createDirectories(home.resolve("tools").resolve(tool).resolve(dir));
        Properties p = new Properties();
        p.load(new StringReader(properties));
        String vendor = p.getProperty("vendor", "").trim();
        Installed installed = new Installed(Tool.parse(tool), Version.parse(p.getProperty("version").trim()),
                vendor.isEmpty() ? null : Vendor.fromFoojay(vendor), path, Boolean.parseBoolean(p.getProperty("fx")));
        Store.writeMarker(path, installed, "https://example.invalid/" + dir, false);
        return path;
    }
}
