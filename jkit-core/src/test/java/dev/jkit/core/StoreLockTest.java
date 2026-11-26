package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The install lock, taken by Installer and by commands: one open file per process. */
class StoreLockTest {
    @TempDir
    Path tmp;

    @Test
    void takenTwiceByOneThreadItIsHeldUntilBothClose() throws Exception {
        JkitHome home = new JkitHome(tmp.resolve("home"));
        Store store = new Store(home);
        Store.Lock outer = store.lock();
        // the second take used to open the file again: closing it let go of the OS lock on Unix
        store.lock(home.lockFile(), "waiting…").close();
        assertThat(probe(home.lockFile())).isEqualTo("held");
        // another thread of this jkit waits its turn
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> {
            try (Store.Lock lock = store.lock()) {
                // got it
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        assertThat(other).isNotDone();
        outer.close();
        other.get(30, TimeUnit.SECONDS);
        assertThat(probe(home.lockFile())).isEqualTo("free");
    }

    /** What another process finds: "held" or "free". */
    private static String probe(Path file) throws Exception {
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(jvm, "-cp", System.getProperty("java.class.path"), Probe.class.getName(),
                file.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return out;
    }

    /** Another jkit: tries the lock on {@code args[0]} and says whether it was held. */
    public static final class Probe {
        private Probe() { }

        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                System.out.println(lock == null ? "held" : "free");
            }
        }
    }
}
