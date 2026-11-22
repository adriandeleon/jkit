package dev.jkit.core;

import dev.jkit.core.ToolState.Source;
import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads, per tool, where it comes from: shell choice, project pin, global link, or something
 * outside jkit. Shared by status, env and doctor, and by sync and the auto-switch hook later.
 * Never uses the network.
 */
public final class ToolStates {
    /** The tools in display order. */
    public static final List<Tool> TOOLS = List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.GRAALVM);

    /** Reads a link's target (OS-specific); null when there is no link. */
    @FunctionalInterface
    public interface Links {
        Path read(Path link) throws IOException;
    }

    /**
     * @param project      the project found from the working directory, or empty
     * @param projectError why the project file couldn't be read (broken TOML), or null
     */
    public record Result(Optional<ProjectSpec> project, String projectError, List<ToolState> tools) {
        public ToolState get(Tool tool) {
            return tools.stream().filter(t -> t.tool() == tool).findFirst().orElseThrow();
        }
    }

    private ToolStates() { }

    public static Result read(Map<String, String> env, Path cwd, JkitHome home, Store store, Config config,
                              Links links) throws IOException {
        Optional<ProjectSpec> project = Optional.empty();
        String projectError = null;
        try {
            project = ProjectFile.find(cwd, env);
        } catch (JkitException e) {
            projectError = e.getMessage();
        }
        List<ToolState> tools = new ArrayList<>();
        for (Tool tool : TOOLS) {
            PinnedTool pin = project.map(p -> p.get(tool)).orElse(null);
            tools.add(read(tool, env, home, store, config, links, pin));
        }
        return new Result(project, projectError, List.copyOf(tools));
    }

    private static ToolState read(Tool tool, Map<String, String> env, JkitHome home, Store store, Config config,
                                  Links links, PinnedTool pin) throws IOException {
        Path shellChoice = pathOrNull(env.get(ShellEnv.stateVariable(tool)));
        if (shellChoice == null && tool == Tool.GRAALVM && env.containsKey(ShellEnv.GRAALVM_FOLLOWS)) {
            shellChoice = pathOrNull(env.get(ShellEnv.stateVariable(Tool.JDK))); // a GraalVM JDK chosen with use
        }
        Installed pinInstall = pin == null ? null : resolve(store, config, pin).orElse(null);
        Path global = links.read(home.global(tool));
        boolean globalMissing = global != null && !Files.isDirectory(global);
        Path current = pathOrNull(env.get(tool.homeVariable()));

        Source wanted;
        Path wantedHome;
        if (shellChoice != null) {
            wanted = Source.SHELL;
            wantedHome = shellChoice;
        } else if (pin != null) {
            wanted = Source.PROJECT;
            wantedHome = pinInstall == null ? null : pinInstall.home();
        } else if (global != null && !globalMissing) {
            wanted = Source.GLOBAL;
            wantedHome = global;
        } else {
            wanted = Source.NONE;
            wantedHome = null;
        }

        Source source;
        if (current == null) {
            source = Source.NONE;
        } else if (shellChoice != null && same(current, shellChoice)) {
            source = Source.SHELL;
        } else if (pinInstall != null && same(current, pinInstall.home())) {
            source = Source.PROJECT;
        } else if (same(current, home.global(tool)) || (global != null && same(current, global))) {
            source = Source.GLOBAL;
        } else if (real(current).startsWith(real(home.root()))) {
            source = Source.STALE;
        } else {
            source = Source.OUTSIDE;
        }
        Installed active = current == null ? null : installAt(store, tool, current);
        return new ToolState(tool, shellChoice, pin, pinInstall, global, globalMissing, current, active, source,
                wanted, wantedHome);
    }

    /**
     * The install a pin picks: its vendor if it names one, else the user's default vendor, else the
     * newest match from any vendor. Plain builds beat JavaFX builds unless the pin asks for fx.
     */
    public static Optional<Installed> resolve(Store store, Config config, PinnedTool pin) throws IOException {
        List<Installed> all = store.find(pin.tool(), pin.version(), pin.vendor());
        List<Installed> plain = all.stream().filter(i -> !i.fx()).toList();
        List<Installed> withFx = all.stream().filter(Installed::fx).toList();
        List<Installed> matches = pin.fx() ? withFx : plain.isEmpty() ? withFx : plain;
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (pin.vendor() == null) {
            Vendor preferred = config.defaultVendor(pin.tool());
            for (Installed i : matches) {
                if (preferred != null && preferred.equals(i.vendor())) {
                    return Optional.of(i);
                }
            }
        }
        return Optional.of(matches.getFirst());
    }

    /**
     * {@code JAVA_VERSION} from a JDK's {@code release} file, e.g. {@code 21.0.5}, so a JDK set
     * outside jkit can still be named; null when there is none.
     */
    public static String releaseVersion(Path jdkHome) {
        Path release = jdkHome.resolve("release");
        if (!Files.isRegularFile(release)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String version = p.getProperty("JAVA_VERSION");
        return version == null ? null : version.replace("\"", "").trim();
    }

    private static Installed installAt(Store store, Tool tool, Path path) throws IOException {
        for (Installed i : store.list(tool)) {
            if (same(path, i.home())) {
                return i;
            }
        }
        return null;
    }

    private static Path pathOrNull(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    /** Same folder, following links (the global link and its target are the same install). */
    static boolean same(Path a, Path b) {
        return real(a).equals(real(b));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
