package dev.jkit.platform;

import dev.jkit.model.JkitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Windows: directory junctions, which need no admin rights or Developer Mode. Java has no API to
 * create one, so this runs {@code cmd /c mklink /J}. Untested in the prototype (built on Linux).
 */
final class WindowsOs implements OsServices {

    @Override
    public void pointLink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        removeLink(link);
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        try {
            String output = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) {
                throw new IOException("mklink /J failed: " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public Path readLink(Path link) throws IOException {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return link.toRealPath();
    }

    @Override
    public void removeLink(Path link) throws IOException {
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(link); // deletes the junction, not its target
        }
    }

    @Override
    public String hook(String shell, Path root) {
        if (shell.equals("pwsh") || shell.equals("powershell")) {
            return Hooks.powershell(root);
        }
        throw new JkitException("unsupported shell on Windows: " + shell, "shells: pwsh, powershell");
    }
}
