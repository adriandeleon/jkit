package dev.jkit.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/** Reads the version Maven filtered into jkit-version.properties at build time. */
final class VersionProvider implements IVersionProvider {

    static String version() {
        Properties p = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream("/jkit-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // fall through to "unknown"
        }
        return p.getProperty("version", "unknown");
    }

    @Override
    public String[] getVersion() {
        return new String[] {"jkit " + version()};
    }
}
