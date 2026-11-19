package dev.jkit.core;

import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.nio.file.Path;

/** One installed version. {@code tool} is the store tool (JDK for GraalVM installs). */
public record Installed(Tool tool, Version version, Vendor vendor, Path home) {

    public String id() {
        return dirName(version, vendor);
    }

    public static String dirName(Version version, Vendor vendor) {
        return vendor == null ? version.raw() : version.raw() + "-" + vendor.name();
    }

    public String label() {
        return version + (vendor == null ? "" : " " + vendor.name());
    }
}
