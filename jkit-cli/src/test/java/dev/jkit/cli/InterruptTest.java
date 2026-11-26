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

/** Ctrl-C while jkit works: jkit cleans up, then ends from SIGINT, so a bash script running it stops too. */
@DisabledOnOs(OS.WINDOWS)
class InterruptTest {
    @TempDir
    Path tmp;

    @Test
    void sigintEndsJkitWithSigintAndDeletesTheHooksEnvFile() throws Exception {
        assertThat(endedBy("INT")).isEqualTo("2 0"); // signal 2, no exit code
        // as the hook's jkit function runs it: bash skips the function's rm after a death by SIGINT
        assertThat(envFile).doesNotExist();
    }

    @Test
    void sigtermExits143() throws Exception {
        assertThat(endedBy("TERM")).isEqualTo("0 143"); // the exit code agents and scripts expect
        assertThat(envFile).exists(); // the hook's jkit function deletes it
    }

    private Path envFile;

    /** Sends jkit search the signal while it waits on the network; how jkit ended, as perl saw it. */
    private String endedBy(String signal) throws Exception {
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
            Path temp = Files.createDirectories(tmp.resolve("temp"));
            envFile = Files.createFile(temp.resolve("jkit-env.Xy34Zw"));
            pb.environment().put("TMPDIR", temp.toString());
            pb.environment().put("JKIT_ENV_FILE", envFile.toString());
            Process p = pb.start();
            try (Socket request = silent.accept()) { // jkit is running, its signal handlers in place
                ProcessHandle jkit = p.toHandle().children().findFirst().orElseThrow();
                new ProcessBuilder("kill", "-" + signal, Long.toString(jkit.pid())).start().waitFor();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
                return out;
            } finally {
                p.destroyForcibly();
            }
        }
    }
}
