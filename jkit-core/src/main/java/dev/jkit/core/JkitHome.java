package dev.jkit.core;

import dev.jkit.model.Tool;
import java.nio.file.Path;
import java.util.Map;

/** The ~/.jkit layout. */
public record JkitHome(Path root) {

    public static JkitHome fromEnv(Map<String, String> env) {
        String override = env.get("JKIT_HOME");
        if (override != null && !override.isBlank()) {
            return new JkitHome(Path.of(override).toAbsolutePath());
        }
        return new JkitHome(Path.of(System.getProperty("user.home"), ".jkit"));
    }

    public Path toolsRoot() { return root.resolve("tools"); }

    public Path tools(Tool tool) { return toolsRoot().resolve(tool.storeDir()); }

    public Path global(Tool tool) { return root.resolve("global").resolve(tool.id()); }

    public Path tmp() { return root.resolve("tmp"); }

    public Path index() { return root.resolve("index"); }

    public Path lockFile() { return root.resolve(".lock"); }

    public Path config() { return root.resolve("config.toml"); }
}
