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
}
