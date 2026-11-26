package dev.jkit.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** HKCU\Environment through Windows PowerShell, on a variable of the test's own. */
@EnabledOnOs(OS.WINDOWS)
class WindowsUserEnvTest {

    @Test
    void writesReadsBackAndKeepsAnExpandableValueExpandable() throws Exception {
        String name = "JKIT_TEST_" + Long.toHexString(System.nanoTime());
        WindowsUserEnv env = new WindowsUserEnv();
        try {
            // a % makes a new value expandable; a later write keeps it so, unexpanded
            env.write(Map.of(name, "%USERPROFILE%\\x;C:\\Program Files\\é ü"));
            assertThat(env.read(List.of(name, name + "_MISSING")))
                    .containsExactly(Map.entry(name, "%USERPROFILE%\\x;C:\\Program Files\\é ü"));
            env.write(Map.of(name, "%USERPROFILE%\\y"));
            assertThat(env.read(List.of(name))).containsEntry(name, "%USERPROFILE%\\y");
            assertThat(env.systemPath()).containsIgnoringCase("System32").doesNotContain("%SystemRoot%");
        } finally {
            Map<String, String> remove = new HashMap<>();
            remove.put(name, null);
            env.write(remove);
        }
        assertThat(new WindowsUserEnv().read(List.of(name))).isEmpty();
    }
}
