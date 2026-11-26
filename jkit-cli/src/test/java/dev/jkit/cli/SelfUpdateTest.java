package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sun.net.httpserver.HttpServer;
import dev.jkit.core.Signatures;
import dev.jkit.core.WorkDirs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** jkit self-update against a release mirror on localhost. */
class SelfUpdateTest {
    @TempDir
    Path tmp;
    HttpServer server;
    /** What the mirror serves as SHA256SUMS.asc (null: a 404); the test's check takes "good" as a good signature. */
    String signature = "good";
    /** Run by the mirror before it serves the release archive, while self-update downloads it. */
    volatile Runnable duringDownload;
    Map<String, String> env;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream json = new ByteArrayOutputStream();
    /** What the command said on stderr (with --json, its prose). */
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Platform.current().isWindows());
        serve("9.9.9");
    }

    /** Serves {@code version} as the newest release, replacing what was served. */
    private void serve(String version) throws Exception {
        if (server != null) {
            server.stop(0);
        }
        String platform = Platform.current().os() + "-" + Platform.current().arch();
        String archiveName = "jkit-" + version + "-" + platform + ".tar.gz";
        byte[] archive = archive("jkit " + version);
        String sums = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)) + "  " + archiveName + "\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/download/v" + version + "/" + archiveName) && duringDownload != null) {
                duringDownload.run();
            }
            byte[] body = path.equals("/latest/download/SHA256SUMS") ? sums.getBytes(StandardCharsets.UTF_8)
                    : path.equals("/download/v" + version + "/SHA256SUMS.asc")
                            ? (signature == null ? null : signature.getBytes(StandardCharsets.UTF_8))
                    : path.equals("/download/v" + version + "/" + archiveName) ? archive : null;
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        env = new HashMap<>(Map.of("HOME", tmp.toString(), "JKIT_HOME", tmp.resolve(".jkit").toString(),
                "JKIT_SOURCES_RELEASES_URL", "http://localhost:" + server.getAddress().getPort() + "/"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] archive(String says) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(raw))) {
            byte[] body = ("#!/bin/sh\necho '" + says + "'\n").getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry("jkit");
            e.setMode(0755);
            e.setSize(body.length);
            tar.putArchiveEntry(e);
            tar.write(body);
            tar.closeArchiveEntry();
        }
        return raw.toByteArray();
    }

    private String run(Path binary, String current, boolean check, boolean asJson) throws Exception {
        out.reset();
        err.reset();
        SelfUpdateCommand cmd = new SelfUpdateCommand();
        cmd.check = check;
        cmd.json.json = asJson;
        cmd.sumsCheck = (data, sig) -> new String(sig, StandardCharsets.UTF_8).equals("good")
                ? new Signatures.Result(Signatures.Outcome.GOOD, "jkit", "jkit")
                : new Signatures.Result(Signatures.Outcome.UNKNOWN_KEY, null, "made with key X");
        cmd.run(Context.forEnvOnline(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)), binary, current);
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path binary(String dir) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve(dir));
        return Files.writeString(bin.resolve("jkit"), "old");
    }

    @Test
    void replacesAnArchiveInstallAndSaysSo() throws Exception {
        Path binary = binary("bin");
        String said = run(binary, "0.3.0", false, false);
        assertThat(said).contains("updated jkit 0.3.0 → 9.9.9").contains("updated");
        assertThat(Files.readString(binary)).contains("jkit 9.9.9");
        assertThat(run(binary, "9.9.9", false, false)).contains("jkit 9.9.9 is the newest release");
    }

    @Test
    void downloadsWithoutTheInstallLockInAFolderItHolds() throws Exception {
        Path binary = binary("bin");
        List<String> seen = new CopyOnWriteArrayList<>();
        duringDownload = () -> {
            try {
                // another jkit's install or prune can take the lock meanwhile
                Thread other = new Thread(() -> {
                    try (var _ = Context.forEnvOnline(env, new PrintStream(OutputStream.nullOutputStream()))
                            .store.lock()) {
                        seen.add("locked");
                    } catch (Exception e) {
                        seen.add(e.toString());
                    }
                });
                other.start();
                other.join(10_000);
                // and finds the download's folder in use, not a leftover to delete
                try (Stream<Path> work = Files.list(tmp.resolve(".jkit/tmp"))) {
                    work.forEach(p -> seen.add(p.getFileName().toString().replaceAll("\\d+$", "")
                            + " " + WorkDirs.use(p)));
                }
            } catch (Exception e) {
                seen.add(e.toString());
            }
        };
        run(binary, "0.3.0", false, false);
        assertThat(seen).containsExactly("locked", "install-self- IN_USE");
        assertThat(Files.readString(binary)).contains("jkit 9.9.9");
        try (Stream<Path> left = Files.list(tmp.resolve(".jkit/tmp"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aPreReleaseMarkedAsTheLatestReleaseOnlyReplacesAPreRelease() throws Exception {
        // signed, and marked as the latest release: a release candidate still doesn't replace a release
        serve("9.9.9-rc.1");
        Path binary = binary("bin");
        assertThat(run(binary, "0.3.0", false, false)).contains("jkit 0.3.0 is up to date: the newest release, "
                + "9.9.9-rc.1, is a pre-release, which self-update installs only over a pre-release");
        assertThat(run(binary, "0.3.0", true, true)).contains("\"status\" : \"up-to-date\"");
        assertThat(Files.readString(binary)).isEqualTo("old");
        // over a pre-release it does
        assertThat(run(binary, "9.9.9-beta.1", false, false)).contains("updated jkit 9.9.9-beta.1 → 9.9.9-rc.1");
        assertThat(Files.readString(binary)).contains("jkit 9.9.9-rc.1");
    }

    @Test
    void checkChangesNothing() throws Exception {
        Path binary = binary("bin");
        assertThat(run(binary, "0.3.0", true, false)).contains("jkit 9.9.9 is out (this is 0.3.0)")
                .contains("to update: jkit self-update");
        assertThat(Files.readString(binary)).isEqualTo("old");
    }

    @Test
    void aHomebrewInstallIsLeftToHomebrew() throws Exception {
        Path binary = binary("Cellar/jkit/0.3.0/bin");
        String said = run(binary, "0.3.0", false, true);
        assertThat(said).contains("\"status\" : \"managed\"").contains("\"installedWith\" : \"homebrew\"")
                .contains("\"command\" : \"brew upgrade jkit\"");
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("jkit 9.9.9 is out (this is 0.3.0); jkit came from Homebrew").contains("  brew upgrade jkit");
        assertThat(Files.readString(binary)).isEqualTo("old");
    }

    @Test
    void checkAndPackageManagersNeedNoSignature() throws Exception {
        // a release from before SHA256SUMS was signed: saying it's out, or how to get it, is fine
        signature = null;
        Path binary = binary("bin");
        assertThat(run(binary, "0.3.0", true, false)).contains("jkit 9.9.9 is out (this is 0.3.0)");
        assertThat(run(binary, "9.9.9", false, false)).contains("jkit 9.9.9 is the newest release");
        assertThat(run(binary("Cellar/jkit/0.3.0/bin"), "0.3.0", false, false)).contains("brew upgrade jkit");
        // so is one whose signature is bad: only replacing the binary trusts it
        signature = "forged";
        assertThat(run(binary, "0.3.0", true, false)).contains("jkit 9.9.9 is out (this is 0.3.0)");
        assertThat(Files.readString(binary)).isEqualTo("old");
    }

    @Test
    void anUnsignedReleaseIsNotInstalledAndSaysWhy() throws Exception {
        signature = null;
        Path binary = binary("bin");
        assertThatThrownBy(() -> run(binary, "0.3.0", false, false))
                .hasMessageContaining("jkit 9.9.9 has no signed SHA256SUMS (no SHA256SUMS.asc on its release page)")
                .hasMessageContaining("nothing was changed")
                .satisfies(e -> assertThat(((JkitException) e).hints())
                        .noneMatch(h -> h.contains("try again later")));
        assertThat(Files.readString(binary)).isEqualTo("old");
    }

    @Test
    void refusesSumsNotSignedByJkitsKey() throws Exception {
        Path binary = binary("bin");
        signature = "forged";
        assertThatThrownBy(() -> run(binary, "0.3.0", false, false))
                .hasMessageContaining("the SHA256SUMS of jkit 9.9.9 isn't signed by jkit's release key")
                .hasMessageContaining("nothing was changed");
        assertThat(Files.readString(binary)).isEqualTo("old");
    }
}
