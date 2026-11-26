package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.JkitHome;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.platform.UserEnv;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * jkit env --install and --uninstall: the global versions in the user's own environment variables
 * (Windows), so programs started outside a hooked PowerShell (cmd.exe, IDEs, Explorer) get them.
 * Every value names a global link, never an install, so jkit use --global needs no change here
 * afterwards. The same variables and PATH entries as the PowerShell hook, which leaves them be.
 */
final class UserEnvSetup {
    /** The variables, as the PowerShell hook sets them; GRAALVM_HOME never goes on PATH. */
    static final List<Tool> TOOLS = List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT, Tool.GRAALVM);

    private UserEnvSetup() {
    }

    /** The names install and uninstall read. */
    static List<String> names() {
        List<String> names = new ArrayList<>();
        TOOLS.forEach(t -> names.add(t.homeVariable()));
        names.add("Path");
        return names;
    }

    /**
     * What install writes, recorded in {@code report}: for each tool with a global version, its
     * variable (replacing another value, which a note keeps) and its bin first on PATH. Tools
     * without one are left out: a JAVA_HOME naming nothing breaks programs that could otherwise find
     * java on PATH, and a MAVEN_HOME of the user's own stays theirs.
     */
    static Map<String, String> install(JkitHome home, Map<String, String> current, Changes report) {
        Map<String, String> writes = new LinkedHashMap<>();
        List<String> bins = new ArrayList<>();
        for (Tool tool : TOOLS) {
            Path link = home.global(tool).toAbsolutePath().normalize();
            if (!Files.exists(link)) {
                continue;
            }
            String var = tool.homeVariable();
            String before = current.get(var);
            if (!same(before, link.toString())) {
                writes.put(var, link.toString());
                report.userEnv(var, "set", link.toString());
                if (before != null && !before.isBlank()) {
                    report.note("replaced " + var + "=" + before + ", which was set before");
                }
            }
            if (tool.onPath()) {
                bins.add(link.resolve("bin").toString());
            }
        }
        List<String> path = entries(current.get("Path"));
        List<String> added = new ArrayList<>();
        for (String bin : bins) {
            if (path.stream().noneMatch(e -> same(e, bin))) {
                added.add(bin);
                report.userEnv("PATH", "add", bin);
            }
        }
        if (!added.isEmpty()) {
            // first: other folders in the user PATH (an old JDK's bin) mustn't win over jkit's; the
            // rest stays exactly as written
            String rest = current.getOrDefault("Path", "");
            writes.put("Path", String.join(File.pathSeparator, added) + (rest.isEmpty() ? "" : File.pathSeparator + rest));
        }
        return writes;
    }

    /** What uninstall writes: only the values install set, so a variable of the user's own stays. */
    static Map<String, String> uninstall(JkitHome home, Map<String, String> current, Changes report) {
        Map<String, String> writes = new LinkedHashMap<>();
        List<String> bins = new ArrayList<>();
        for (Tool tool : TOOLS) {
            String link = home.global(tool).toAbsolutePath().normalize().toString();
            String var = tool.homeVariable();
            if (same(current.get(var), link)) {
                writes.put(var, null);
                report.userEnv(var, "unset", null);
            }
            if (tool.onPath()) {
                bins.add(Path.of(link, "bin").toString());
            }
        }
        // only jkit's entries go: the rest, empty ones included, stays exactly as written
        String raw = current.getOrDefault("Path", "");
        List<String> parts = List.of(raw.split(Pattern.quote(File.pathSeparator), -1));
        List<String> kept = new ArrayList<>();
        for (String entry : parts) {
            if (!entry.isBlank() && bins.stream().anyMatch(b -> same(entry, b))) {
                report.userEnv("PATH", "remove", entry);
            } else {
                kept.add(entry);
            }
        }
        if (kept.size() != parts.size()) {
            writes.put("Path", String.join(File.pathSeparator, kept));
        }
        return writes;
    }

    /**
     * The folders in the system PATH, which Windows puts before the user's, that hold one of jkit's
     * commands: those win over jkit's global versions in programs started outside a hooked shell.
     */
    static List<String> shadowing(String systemPath) {
        List<String> found = new ArrayList<>();
        for (String entry : entries(systemPath)) {
            Path dir = Fs.path(entry);
            if (dir == null) {
                continue;
            }
            for (String command : List.of("java.exe", "mvn.cmd", "gradle.bat", "sbt.bat")) {
                try {
                    if (Files.isRegularFile(dir.resolve(command))) {
                        found.add(entry + " (" + command + ")");
                    }
                } catch (InvalidPathException e) {
                    // not a folder name: nothing to find there
                }
            }
        }
        return found;
    }

    /**
     * Whether the user's environment names jkit's global version of {@code tool}; null when it
     * can't be read here (not Windows, or the read failed).
     */
    static Boolean follows(Context ctx, Tool tool) {
        UserEnv env = ctx.os.userEnv();
        if (env == null) {
            return null;
        }
        try {
            return same(env.read(List.of(tool.homeVariable())).get(tool.homeVariable()),
                    ctx.home.global(tool).toAbsolutePath().normalize().toString());
        } catch (IOException e) {
            return null;
        }
    }

    /** Runs install or uninstall against the user's environment, and says what changed. */
    static int run(Context ctx, boolean install, boolean json) throws IOException {
        UserEnv env = ctx.os.userEnv();
        if (env == null) {
            throw new JkitException(
                    "jkit env --" + (install ? "install" : "uninstall") + " is for Windows, where programs get their "
                            + "variables from the user's environment",
                    "on macOS and Linux the shell's startup file is the place: jkit hook --"
                            + (install ? "install" : "uninstall"));
        }
        List<String> missing = new ArrayList<>();
        for (Tool tool : TOOLS) {
            if (!Files.exists(ctx.home.global(tool))) {
                missing.add(tool.id());
            }
        }
        if (install && missing.size() == TOOLS.size()) {
            throw new JkitException("no global versions yet, so there is nothing to set",
                    "set one first: jkit install jdk@21, or jkit use jdk@21 --global; then jkit env --install");
        }
        Map<String, String> current = env.read(names());
        Changes report = new Changes(ctx);
        Map<String, String> writes = install
                ? install(ctx.home, current, report)
                : uninstall(ctx.home, current, report);
        if (!writes.isEmpty()) {
            env.write(writes);
        }
        if (install) {
            if (!writes.isEmpty()) {
                report.note("programs started from now on get them (a new terminal, an IDE you restart); "
                        + "running ones, this shell included, keep what they have");
            }
            report.note("jkit use … --global needs nothing more: these name the global links, which it moves");
            if (!missing.isEmpty()) {
                report.note("no global " + String.join(", ", missing) + " yet; after you set one, run jkit env --install again");
            }
            for (String entry : shadowing(env.systemPath())) {
                report.note("warning: the system PATH, which comes before yours, has " + entry
                        + "; it wins outside a hooked PowerShell. Remove it from the system PATH (needs admin)");
            }
        } else if (!writes.isEmpty()) {
            report.note("programs started from now on no longer get them; running ones keep what they have");
        }
        String message = writes.isEmpty()
                ? (install ? "your user environment already points at jkit's global versions; nothing changed"
                        : "your user environment has nothing jkit set; nothing changed")
                : (install ? "set jkit's global versions in your user environment"
                        : "removed jkit's global versions from your user environment");
        PrintStream say = json ? ctx.err : ctx.out;
        say.println(message);
        report.print(say);
        if (json) {
            ctx.out.println(JsonOut.write(JsonOut.fields("action", install ? "install" : "uninstall",
                    "changed", !writes.isEmpty(), "changes", report.json(), "notes", report.notes())));
        }
        return 0;
    }

    /** Folder names as Windows compares them: no case, no trailing separator. */
    static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return trim(a).toLowerCase(Locale.ROOT).equals(trim(b).toLowerCase(Locale.ROOT));
    }

    private static String trim(String s) {
        s = s.strip();
        while (s.length() > 1 && (s.endsWith("\\") || s.endsWith("/"))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** PATH entries in order, empty ones left out. */
    static List<String> entries(String path) {
        List<String> list = new ArrayList<>();
        if (path != null) {
            for (String e : path.split(Pattern.quote(File.pathSeparator))) {
                if (!e.isBlank()) {
                    list.add(e);
                }
            }
        }
        return list;
    }
}
