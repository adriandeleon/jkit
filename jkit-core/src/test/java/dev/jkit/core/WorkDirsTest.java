package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkDirsTest {
    @TempDir
    Path tmp;

    @Test
    void anInterruptDeletesTheFoldersStillInUse() throws Exception {
        Path inUse = Files.createDirectories(tmp.resolve("install-1"));
        Files.writeString(inUse.resolve("jdk.tar.gz"), "partial");
        Path done = Files.createDirectories(tmp.resolve("install-2"));
        WorkDirs.add(inUse);
        WorkDirs.add(done);
        WorkDirs.remove(done); // that install finished

        assertThat(WorkDirs.deleteAll()).containsExactly(inUse);
        assertThat(inUse).doesNotExist();
        assertThat(done).exists();
        assertThat(WorkDirs.deleteAll()).isEmpty();
    }

    @Test
    void aFolderThisJkitMadeIsInUseUntilItIsDone() throws Exception {
        Path work = WorkDirs.create(tmp, "install-");
        assertThat(work.getFileName().toString()).startsWith("install-");
        assertThat(work.resolve(WorkDirs.LOCK)).isRegularFile();
        assertThat(WorkDirs.inUse(work)).isTrue();
        WorkDirs.remove(work);
        assertThat(WorkDirs.inUse(work)).isFalse(); // its lock is let go: a leftover, if it isn't deleted
        // one without a lock file: an older jkit's
        assertThat(WorkDirs.inUse(Files.createDirectories(tmp.resolve("install-old")))).isFalse();
        // and an interrupt deletes one, lock file and all
        Path interrupted = WorkDirs.create(tmp, "install-");
        assertThat(WorkDirs.deleteAll()).containsExactly(interrupted);
        assertThat(interrupted).doesNotExist();
    }

    @Test
    void anInterruptWaitsForAMoveIntoToolsAndDoesntReportItsFolder() throws Exception {
        Path work = WorkDirs.create(tmp, "install-");
        Path out = Files.createDirectories(work.resolve("out").resolve("jdk"));
        Path tools = Files.createDirectories(tmp.resolve("tools"));
        CompletableFuture<List<Path>> deleted = new CompletableFuture<>();
        WorkDirs.moveIn(() -> {
            // Ctrl-C meanwhile: its delete waits until the install is in tools/
            Thread interrupt = Thread.ofPlatform().start(() -> deleted.complete(WorkDirs.deleteAll()));
            // until it is blocked on the move, not a fixed sleep: a slow machine mustn't pass by luck
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (interrupt.getState() != Thread.State.BLOCKED && !deleted.isDone() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(interrupt.getState()).isEqualTo(Thread.State.BLOCKED);
            assertThat(deleted).isNotDone();
            Files.move(out, tools.resolve("jdk"));
        }, out);
        // the work folder still goes, but the install is in place: not "nothing was installed"
        assertThat(deleted.get()).isEmpty();
        assertThat(work).doesNotExist();
        assertThat(tools.resolve("jdk")).isDirectory();
    }

    /** Another jkit, downloading: holds a work folder in {@code args[0]}, prints it, waits for stdin to close. */
    public static final class Holder {
        private Holder() { }

        public static void main(String[] args) throws Exception {
            System.out.println(WorkDirs.create(Path.of(args[0]), "install-"));
            System.out.flush();
            System.in.read();
            System.exit(0); // gone without cleaning up, as when killed
        }
    }
}
