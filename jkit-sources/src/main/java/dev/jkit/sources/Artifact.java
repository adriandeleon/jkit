package dev.jkit.sources;

import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.net.URI;

/**
 * One downloadable distribution, resolved for one platform.
 *
 * @param vendor null for Maven and Gradle
 * @param checksumAlgorithm "sha256", "sha512", ... or null when the source publishes none
 * @param fx true for a JDK build with JavaFX bundled
 * @param signature the publisher's detached OpenPGP signature of the download, or null when there is none
 */
public record Artifact(
        Tool tool,
        Version version,
        Vendor vendor,
        URI url,
        String fileName,
        String archiveType,
        String checksumAlgorithm,
        String checksum,
        boolean fx,
        URI signature) {

    /** An artifact with no listed signature. */
    public Artifact(Tool tool, Version version, Vendor vendor, URI url, String fileName, String archiveType,
            String checksumAlgorithm, String checksum, boolean fx) {
        this(tool, version, vendor, url, fileName, archiveType, checksumAlgorithm, checksum, fx, null);
    }

    public String label() {
        return tool.id() + " " + version + (vendor == null ? "" : " " + vendor.name()) + (fx ? " fx" : "");
    }
}
