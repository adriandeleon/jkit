package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sun.net.httpserver.HttpServer;
import dev.jkit.model.Platform;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
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
    Map<String, String> env;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream json = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Platform.current().isWindows());
        String platform = Platform.current().os() + "-" + Platform.current().arch();
        String archiveName = "jkit-9.9.9-" + platform + ".tar.gz";
        byte[] archive = archive("jkit 9.9.9");
        String sums = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)) + "  " + archiveName + "\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body = path.equals("/latest/download/SHA256SUMS") ? sums.getBytes(StandardCharsets.UTF_8)
                    : path.equals("/download/v9.9.9/" + archiveName) ? archive : null;
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
        SelfUpdateCommand cmd = new SelfUpdateCommand();
        cmd.check = check;
        cmd.json.json = asJson;
        cmd.run(Context.forEnvOnline(env, new PrintStream(out, true, StandardCharsets.UTF_8)), binary, current);
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
        assertThat(Files.readString(binary)).isEqualTo("old");
    }
}
