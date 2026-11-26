package dev.jkit.platform;

import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Linux and macOS: symlinks, replaced atomically by renaming a new link over the old one. */
final class UnixOs implements OsServices {

    @Override
    public void pointLink(Path link, Path target) throws IOException {
        Perms.mkdirs(link.getParent());
        // a name of its own, so two jkit runs switching at once don't trip over each other's temp link
        Path tmp = link.resolveSibling("." + link.getFileName() + ".new-" + ProcessHandle.current().pid()
                + "-" + Long.toHexString(System.nanoTime()));
        // relative, so moving the jkit folder keeps the link valid
        Files.createSymbolicLink(tmp, link.getParent().toAbsolutePath().relativize(target.toAbsolutePath()));
        try {
            Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp); // e.g. link is a real folder someone made: don't leave .jdk.new-… behind
            throw e;
        }
    }

    @Override
    public Path readLink(Path link) throws IOException {
        if (!Files.isSymbolicLink(link)) {
            return null;
        }
        Path target = Files.readSymbolicLink(link);
        return link.getParent().resolve(target).normalize();
    }

    @Override
    public void removeLink(Path link) throws IOException {
        if (Files.isSymbolicLink(link)) {
            Files.delete(link);
        }
    }

    @Override
    public String hook(String shell, Path root, String self) {
        return switch (shell) {
            case "bash", "zsh" -> Hooks.posix(shell, root, self);
            case "fish" -> Hooks.fish(root, self);
            case "pwsh", "powershell" -> Hooks.powershell(root, self);
            default -> throw new JkitException("unsupported shell: " + shell,
                    "shells: bash, zsh, fish, pwsh");
        };
    }
}
