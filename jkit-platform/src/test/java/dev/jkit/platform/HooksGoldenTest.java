package dev.jkit.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares every rendered hook with a recorded copy in {@code hooks-golden/}, so a change to a
 * template shows up as a diff of what users' shells actually run. After an intended change,
 * re-record with {@code -Djkit.hooks.record=<path to src/test/resources/dev/jkit/platform/hooks-golden>}
 * and review the diff.
 *
 * <p>Not on Windows: the recorded paths are POSIX ones, and Windows renders them with backslashes.
 */
@DisabledOnOs(OS.WINDOWS)
class HooksGoldenTest {
    private static final String[] ROOTS = {"/home/u/.jkit", "/tmp/o'brien $HOME `x` \\ \"q\" ‘t’/.jkit"};
    private static final String[] SELVES = {null, "/usr/local/bin/jkit", "/opt/jk it's $x/bin/jkit"};

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (int r = 0; r < ROOTS.length; r++) {
            Path root = Path.of(ROOTS[r]);
            for (int s = 0; s < SELVES.length; s++) {
                String self = SELVES[s];
                String suffix = "-root" + r + "-self" + s;
                cases.add(Arguments.of("bash" + suffix, (Supplier<String>) () -> Hooks.posix("bash", root, self)));
                cases.add(Arguments.of("zsh" + suffix, (Supplier<String>) () -> Hooks.posix("zsh", root, self)));
                cases.add(Arguments.of("fish" + suffix, (Supplier<String>) () -> Hooks.fish(root, self)));
                cases.add(Arguments.of("pwsh" + suffix, (Supplier<String>) () -> Hooks.powershell(root, self)));
            }
        }
        for (String shell : new String[] {"bash", "zsh", "fish"}) {
            cases.add(Arguments.of("auto-switch-" + shell, (Supplier<String>) () -> Hooks.autoSwitch(shell)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rendersAsRecorded(String name, Supplier<String> render) throws IOException {
        String actual = render.get();
        String record = System.getProperty("jkit.hooks.record");
        if (record != null) {
            Files.writeString(Path.of(record, name + ".txt"), actual, StandardCharsets.UTF_8);
            return;
        }
        try (InputStream in = HooksGoldenTest.class.getResourceAsStream("hooks-golden/" + name + ".txt")) {
            assertThat(in).as("hooks-golden/%s.txt", name).isNotNull();
            assertThat(actual).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
