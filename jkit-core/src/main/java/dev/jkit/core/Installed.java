package dev.jkit.core;

import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.nio.file.Path;

/**
 * One installed version. {@code tool} is the store tool (JDK for GraalVM installs); {@code fx} is
 * true for a JDK with JavaFX bundled (false for installs made before jkit recorded it).
 */
public record Installed(Tool tool, Version version, Vendor vendor, Path home, boolean fx) {

    public String id() {
        return dirName(version, vendor, fx);
    }

    /** {@code 21.0.9+10-zulu}, {@code 21.0.9+10-zulu-fx}, {@code 3.9.11}: JavaFX builds sit beside plain ones. */
    public static String dirName(Version version, Vendor vendor, boolean fx) {
        return (vendor == null ? version.raw() : version.raw() + "-" + vendor.name()) + (fx ? "-fx" : "");
    }

    public String label() {
        return version + (vendor == null ? "" : " " + vendor.name()) + (fx ? " fx" : "");
    }
}
