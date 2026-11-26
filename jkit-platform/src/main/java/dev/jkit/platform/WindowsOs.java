package dev.jkit.platform;

import dev.jkit.model.JkitException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Windows: directory junctions, which need no admin rights or Developer Mode. Java has no API to
 * create one, so this runs {@code cmd /c mklink /J}. A junction whose target is gone can't be
 * read back through Java, so each one has a {@code .<name>.target} file beside it naming its
 * target. Untested in the prototype (built on Linux).
 */
final class WindowsOs implements OsServices {

    @Override
    public UserEnv userEnv() {
        return new WindowsUserEnv();
    }

    @Override
    public void pointLink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        // the new junction first, under a name of its own; the old one goes only once it exists
        Path tmp = link.resolveSibling("." + link.getFileName() + ".new-" + ProcessHandle.current().pid()
                + "-" + Long.toHexString(System.nanoTime()));
        mklink(tmp, target.toAbsolutePath());
        // A junction can't be renamed over another, so the swap is remove-then-move. Another jkit can
        // recreate the link in between, and antivirus or the indexer can hold it briefly: retry, and if
        // it still fails put the old link back, so a failed switch never leaves no global version.
        Path old = readLink(link);
        if (old != null && old.toAbsolutePath().normalize().equals(link.toAbsolutePath().normalize())) {
            old = null; // a broken link with no recorded target: nothing to put back (not a loop onto itself)
        }
        IOException last = null;
        boolean moved = false;
        for (int attempt = 1; attempt <= 5 && !moved; attempt++) {
            try {
                removeLink(link);
                Files.move(tmp, link);
                moved = true;
            } catch (IOException e) {
                last = e;
                sleep(100L * attempt);
            }
        }
        if (!moved) {
            Files.deleteIfExists(tmp);
            if (old != null && !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    mklink(link, old);
                    Files.writeString(targetFile(link), old.toString(), StandardCharsets.UTF_8);
                } catch (IOException restore) {
                    last.addSuppressed(restore);
                }
            }
            throw last;
        }
        // The switch is done. The .target file only helps report a link whose target is gone later,
        // so a file held open by antivirus is retried briefly, then left out: never undo the switch.
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Files.writeString(targetFile(link), target.toAbsolutePath().toString(), StandardCharsets.UTF_8);
                return;
            } catch (IOException e) {
                sleep(100L * attempt);
            }
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /**
     * The paths travel in environment variables and sit inside quotes, so cmd treats {@code & ^ |}
     * in them as text and expands {@code %} only once (in the command, not in the values).
     */
    private static void mklink(Path link, Path target) throws IOException {
        // the full path: a bare "cmd" is looked up in the current folder first on Windows
        String systemRoot = java.util.Objects.requireNonNullElse(System.getenv("SystemRoot"), "C:\\Windows");
        String cmd = java.nio.file.Path.of(systemRoot, "System32", "cmd.exe").toString();
        ProcessBuilder pb = new ProcessBuilder(cmd, "/d", "/s", "/c",
                "\"mklink /J \"%JKIT_LINK%\" \"%JKIT_TARGET%\"\"").redirectErrorStream(true);
        pb.environment().put("JKIT_LINK", link.toString());
        pb.environment().put("JKIT_TARGET", target.toString());
        Process p = pb.start();
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
        try {
            return link.toRealPath();
        } catch (NoSuchFileException e) {
            // the target is gone: report where it pointed, so callers see a broken link
            Path recorded = targetFile(link);
            if (Files.isRegularFile(recorded)) {
                return Path.of(Files.readString(recorded, StandardCharsets.UTF_8).trim()).normalize();
            }
            return link;
        }
    }

    @Override
    public void removeLink(Path link) throws IOException {
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(link); // deletes the junction, not its target
        }
        Files.deleteIfExists(targetFile(link));
    }

    private static Path targetFile(Path link) {
        return link.resolveSibling("." + link.getFileName() + ".target");
    }

    @Override
    public String hook(String shell, Path root, String self) {
        if (shell.equals("pwsh") || shell.equals("powershell")) {
            return Hooks.powershell(root, self);
        }
        throw new JkitException("unsupported shell on Windows: " + shell, "shells: pwsh, powershell");
    }
}
