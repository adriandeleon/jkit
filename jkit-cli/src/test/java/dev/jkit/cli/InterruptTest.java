package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Ctrl-C while jkit works: jkit cleans up, then ends from the signal, so a bash script running it stops too. */
@DisabledOnOs(OS.WINDOWS)
class InterruptTest {
    @TempDir
    Path tmp;

    @Test
    void sigintEndsJkitWithSigint() throws Exception {
        Path perl = Path.of("/usr/bin/perl");
        assumeTrue(Files.isExecutable(perl), "no perl to tell how a process ended");
        // a foojay that takes the request and never answers: jkit search waits on it
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            // perl reports the signal that ended jkit (Java can't tell a death by SIGINT from exit
            // code 130); SIGINT at its default, as in an interactive shell, so jkit handles it
            ProcessBuilder pb = new ProcessBuilder(perl.toString(), "-e",
                    "$SIG{INT} = 'DEFAULT'; system(@ARGV); print(($? & 127) . ' ' . ($? >> 8))",
                    jvm, "-cp", System.getProperty("java.class.path"), "dev.jkit.cli.Main", "search", "jdk")
                    .directory(tmp.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().put("JKIT_HOME", tmp.resolve(".jkit").toString());
            pb.environment().put("HOME", tmp.toString());
            pb.environment().put("JKIT_SOURCES_FOOJAY_URL", "http://127.0.0.1:" + silent.getLocalPort() + "/");
            pb.environment().remove("JKIT_NETWORK_OFFLINE");
            Process p = pb.start();
            try (Socket request = silent.accept()) { // jkit is running, its signal handlers in place
                ProcessHandle jkit = p.toHandle().children().findFirst().orElseThrow();
                new ProcessBuilder("kill", "-INT", Long.toString(jkit.pid())).start().waitFor();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(out).isEqualTo("2 0"); // signal 2, no exit code
            } finally {
                p.destroyForcibly();
            }
        }
    }
}
