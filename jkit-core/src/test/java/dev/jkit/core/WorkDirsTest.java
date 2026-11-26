package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
