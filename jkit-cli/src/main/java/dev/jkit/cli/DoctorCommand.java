package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.Store;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolState.Source;
import dev.jkit.core.ToolStates;
import dev.jkit.model.JkitException;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.sources.FoojaySource;
import dev.jkit.sources.GradleSource;
import dev.jkit.sources.JdkHttp;
import dev.jkit.sources.MavenSource;
import dev.jkit.sources.SbtSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "doctor",
        description = {"Checks your setup for problems and says how to fix them. Changes nothing.",
                "Exits 1 when a check fails. Uses the network only with --network."})
final class DoctorCommand implements Callable<Integer> {

    @Option(names = "--network", description = "Also check that foojay, services.gradle.org and Maven Central can be reached.")
    boolean network;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    enum Level {
        OK, INFO, WARN, FAIL;

        String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One line of the report; {@code fix} is null when there is nothing to do. */
    record Finding(String id, Level level, String message, String fix) { }

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.lenient(System.getenv(), System.out);
        List<Finding> findings = run(ctx, Path.of("").toAbsolutePath(), network);
        if (json.json) {
            ctx.out.println(JsonOut.write(json(findings)));
        } else {
            print(findings, ctx.out);
        }
        return findings.stream().anyMatch(f -> f.level() == Level.FAIL) ? 1 : 0;
    }

    // running the checks

    /** The checks in report order. Each is independent: one that throws becomes a fail line. */
    private static final List<Map.Entry<String, Check>> CHECKS = List.of(
            Map.entry("home", DoctorCommand::home),
            Map.entry("hook", DoctorCommand::hook),
            Map.entry("binary", DoctorCommand::binary),
            Map.entry("config", DoctorCommand::config),
            Map.entry("links", DoctorCommand::links),
            Map.entry("outside-vars", DoctorCommand::outsideVars),
            Map.entry("path-order", DoctorCommand::pathOrder),
            Map.entry("store", DoctorCommand::store),
            Map.entry("project", DoctorCommand::project),
            Map.entry("index", DoctorCommand::index),
            Map.entry("java-runs", DoctorCommand::javaRuns),
            Map.entry("network", DoctorCommand::network));

    @FunctionalInterface
    interface Check {
        void run(Report r) throws Exception;
    }

    /** What a check sees, and where it writes its findings. */
    static final class Report {
        final Context ctx;
        final Path cwd;
        final boolean network;
        final List<Finding> findings = new ArrayList<>();
        ToolStates.Result states;
        String statesError;
        boolean statesReported;
        String id;

        Report(Context ctx, Path cwd, boolean network) {
            this.ctx = ctx;
            this.cwd = cwd;
            this.network = network;
        }

        void ok(String message) { add(Level.OK, message, null); }

        void info(String message) { add(Level.INFO, message, null); }

        void warn(String message, String fix) { add(Level.WARN, message, fix); }

        void fail(String message, String fix) { add(Level.FAIL, message, fix); }

        private void add(Level level, String message, String fix) {
            findings.add(new Finding(id, level, message, fix));
        }

        /** The tool states, or null (with a fail line already reported) when they couldn't be read. */
        ToolStates.Result states() {
            if (states == null && statesError != null && !statesReported) {
                statesReported = true;
                fail("can't read which versions are active: " + statesError, null);
            }
            return states;
        }
    }

    private static final boolean WINDOWS = File.separatorChar == '\\';

    /** A path as one shell word in a fix command: ~/... when that is safe, else quoted. */
    static String arg(Path path) {
        String shown = tilde(path);
        if (shown.matches("[A-Za-z0-9_./~+:\\\\-]+")) {
            return shown;
        }
        return dev.jkit.model.ShellQuote.quote(WINDOWS ? "pwsh" : "bash", path.toString());
    }

    /** rm -rf, or PowerShell's Remove-Item on Windows. */
    static String removeAll(String target) {
        return (WINDOWS ? "Remove-Item -Recurse -Force " : "rm -rf ") + target;
    }

    static List<Finding> run(Context ctx, Path cwd, boolean network) {
        Report r = new Report(ctx, cwd, network);
        try {
            r.states = ToolStates.read(ctx.env, cwd, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
        } catch (Exception e) {
            r.statesError = String.valueOf(e.getMessage());
        }
        for (Map.Entry<String, Check> check : CHECKS) {
            r.id = check.getKey();
            try {
                check.getValue().run(r);
            } catch (Exception e) {
                r.fail("the check itself failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "please report this, with the output of jkit doctor --json");
            }
        }
        return List.copyOf(r.findings);
    }

    // the checks

    static void home(Report r) {
        Path root = r.ctx.home.root();
        r.ok("jkit " + VersionProvider.version() + ", " + r.ctx.platform + ", home " + tilde(root));
        if (!Files.isDirectory(root)) {
            r.info(tilde(root) + " doesn't exist yet; the first install creates it");
        } else if (!Files.isWritable(root)) {
            r.fail("can't write to " + tilde(root) + ", so installs and jkit use --global will fail",
                    "check its owner and permissions: " + (WINDOWS ? "Get-Acl " : "ls -ld ") + arg(root));
        }
    }

    static void hook(Report r) {
        String shell = r.ctx.env.get("JKIT_SHELL");
        if (shell != null && !shell.isBlank()) {
            if (r.ctx.env.get("JKIT_ENV_FILE") == null) {
                // the hook exports JKIT_SHELL, so subshells and scripts inherit it without the function
                r.warn("JKIT_SHELL=" + shell + " is inherited, but the jkit function isn't defined here "
                                + "(a subshell or script?), so jkit use can't change this shell",
                        "load the hook in this shell too, or run jkit from your interactive shell");
                return;
            }
            r.ok("shell hook loaded (" + shell + ")");
            return;
        }
        String name = Path.of(r.ctx.env.getOrDefault("SHELL", "bash")).getFileName().toString();
        String fix = switch (name) {
            case "zsh" -> "jkit hook zsh --install, then open a new shell";
            case "fish" -> "jkit hook fish --install, then open a new shell";
            default -> Changes.HOOK_SETUP + ", then open a new shell";
        };
        r.warn("shell hook not loaded: jkit use can't change this shell, and the global versions aren't on PATH", fix);
    }

    static void binary(Report r) throws IOException {
        List<Path> found = which(r.ctx, "jkit");
        if (found.isEmpty()) {
            r.warn("jkit isn't on PATH", "put the folder holding the jkit binary on PATH");
        } else if (found.size() == 1) {
            r.ok("jkit on PATH: " + tilde(found.getFirst()));
        } else {
            r.warn(found.size() + " different jkit binaries on PATH, the first wins: "
                    + String.join(", ", found.stream().map(DoctorCommand::tilde).toList()),
                    "remove the ones you don't use, e.g. an old copy in /usr/local/bin");
        }
    }

    static void config(Report r) throws IOException {
        Path file = r.ctx.home.config();
        if (r.ctx.configError != null) {
            r.fail(r.ctx.configError, "fix " + tilde(file) + ", or move it away to use the defaults");
            return;
        }
        List<String> problems = new ArrayList<>();
        boolean checked = true;
        for (Tool tool : List.of(Tool.JDK, Tool.GRAALVM)) {
            Vendor vendor = r.ctx.config.defaultVendor(tool);
            try {
                r.ctx.foojay().requireKnown(vendor, vendor.name(), tool == Tool.JDK
                        ? "your default JDK vendor (jdk.vendor)" : "your default GraalVM vendor (graalvm.vendor)");
            } catch (JkitException e) {
                if (e.getMessage().startsWith("--offline")) {
                    checked = false; // no cached vendor list yet
                } else {
                    problems.add(e.getMessage() + (e.hints().isEmpty() ? "" : " (" + String.join("; ", e.hints()) + ")"));
                }
            }
        }
        for (String problem : problems) {
            r.warn(problem, "set jdk.vendor / graalvm.vendor in " + tilde(file) + ", or JKIT_JDK_VENDOR");
        }
        if (problems.isEmpty()) {
            r.ok("default vendors: " + r.ctx.config.defaultVendor(Tool.JDK) + " (jdk), "
                    + r.ctx.config.defaultVendor(Tool.GRAALVM) + " (graalvm)"
                    + (Files.isRegularFile(file) ? ", from " + tilde(file) : "")
                    + (checked ? "" : "; not checked against foojay: no cached vendor list yet"));
        }
    }

    static void links(Report r) throws IOException {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        boolean any = false;
        for (ToolState s : states.tools()) {
            if (s.globalMissing()) {
                any = true;
                List<Installed> installed = r.ctx.store.list(s.tool());
                String fix = installed.isEmpty()
                        ? "install one and make it global: jkit install " + s.tool() + "@<version>, then jkit use … --global;"
                                + " or remove the link: " + (WINDOWS ? "Remove-Item " : "rm ") + arg(r.ctx.home.global(s.tool()))
                        : useCommand(s.tool(), installed.getFirst(), true) + ", or remove the link: "
                                + (WINDOWS ? "Remove-Item " : "rm ") + arg(r.ctx.home.global(s.tool()));
                r.fail("global " + s.tool() + " → " + tilde(s.global()) + ", which no longer exists", fix);
            }
            if (s.shellChoice() != null && !Files.isDirectory(s.shellChoice())) {
                any = true;
                r.fail("this shell's " + s.tool() + " (from jkit use) → " + tilde(s.shellChoice()) + ", which no longer exists",
                        "jkit reset " + s.tool());
            }
        }
        if (!any) {
            r.ok("global links and this shell's choices point at existing installs");
        }
    }

    static void outsideVars(Report r) {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        boolean any = false;
        for (ToolState s : states.tools()) {
            String variable = s.tool().homeVariable() + "=" + tilde(s.current());
            if (s.source() == Source.OUTSIDE && s.wanted() != Source.NONE) {
                any = true;
                String loser = switch (s.wanted()) {
                    case SHELL -> "this shell's jkit use";
                    case PROJECT -> states.project().map(ProjectSpec::source).orElse("the project file") + "'s " + s.tool();
                    default -> "the global " + s.tool();
                };
                r.warn(variable + " is set outside jkit and wins over " + loser,
                        "remove the export from your shell profile (~/.bashrc, ~/.zshrc, ~/.profile)");
            } else if (s.source() == Source.STALE) {
                any = true;
                r.warn(variable + " is a jkit install nothing chooses any more", "open a new shell, or jkit reset " + s.tool());
            } else if (s.source() == Source.OUTSIDE) {
                any = true;
                r.info(variable + " is set outside jkit; jkit has no " + s.tool() + " chosen here, so nothing conflicts");
            }
        }
        if (!any) {
            r.ok("no *_HOME variable set outside jkit");
        }
    }

    private static final Map<Tool, String> COMMANDS = Map.of(Tool.JDK, "java", Tool.MAVEN, "mvn", Tool.GRADLE, "gradle", Tool.SBT, "sbt");

    static void pathOrder(Report r) throws IOException {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        List<String> good = new ArrayList<>();
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            ToolState s = states.get(tool);
            if (s.current() == null || !(s.source() == Source.SHELL || s.source() == Source.PROJECT || s.source() == Source.GLOBAL)) {
                continue; // jkit doesn't manage this tool in this shell
            }
            String command = COMMANDS.get(tool);
            // compare folders: on Windows the command is java.exe or mvn.cmd, not plain java or mvn
            Path expectedBin = s.current().resolve("bin");
            List<Path> found = which(r.ctx, command);
            if (found.isEmpty()) {
                r.warn("'" + command + "' isn't on PATH, though " + tool.homeVariable() + " is set by jkit",
                        "open a new shell; if that doesn't help, load the jkit hook after other PATH changes");
            } else if (!sameFile(found.getFirst().getParent(), expectedBin)) {
                r.warn("'" + command + "' on PATH is " + tilde(found.getFirst()) + ", not $" + tool.homeVariable()
                                + "/bin/" + command,
                        "load the jkit hook after other PATH changes in your shell profile, or remove the other " + command);
            } else {
                good.add(command);
            }
        }
        if (!good.isEmpty()) {
            r.ok(String.join(", ", good) + " on PATH " + (good.size() == 1 ? "comes" : "come") + " from jkit's versions");
        }
    }

    static void store(Report r) throws IOException {
        Store store = r.ctx.store;
        int installs = 0;
        boolean any = false;
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            installs += store.list(tool).size();
            Path dir = r.ctx.home.tools(tool);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : children.sorted().toList()) {
                    if (Files.isDirectory(child) && !Files.isSymbolicLink(child)
                            && !Files.isRegularFile(child.resolve(Store.MARKER))) {
                        any = true;
                        r.warn("unfinished install (no " + Store.MARKER + "): " + tilde(child),
                                "remove it: " + removeAll(arg(child)));
                    }
                }
            }
        }
        Path tmp = r.ctx.home.tmp();
        if (Files.isDirectory(tmp) && hasEntries(tmp)) {
            any = true;
            r.warn(human(size(tmp)) + " left in " + tilde(tmp) + " by interrupted downloads",
                    "when no jkit install is running: " + removeAll(arg(tmp) + File.separator + "*")); // the * outside any quotes
        }
        if (!any) {
            r.ok(installs + (installs == 1 ? " install" : " installs") + ", nothing left over");
        }
    }

    static void project(Report r) throws IOException {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        if (states.projectError() != null) {
            r.fail(states.projectError(), "fix the file, or set JKIT_PROJECT_FILE= (empty) to ignore project files");
            return;
        }
        if (states.project().isEmpty() && states.projectSkipped() != null) {
            r.warn(tilde(states.projectSkipped()), states.projectSkipped().contains("UTF-8")
                    ? "rename the folder, or set JKIT_PROJECT_FILE to the file's absolute path"
                    : "jkit ignores project files in folders other users can change (as git does); fix the "
                            + "folder's owner or permissions, or set JKIT_PROJECT_FILE to the file's absolute path");
            return;
        }
        if (states.project().isEmpty()) {
            r.info("no project file here or above (jkit.toml, .jkit.toml, .sdkmanrc, .tool-versions, .java-version)");
            return;
        }
        ProjectSpec p = states.project().get();
        for (String warning : p.warnings()) {
            r.warn(tilde(warning), null);
        }
        List<String> missing = new ArrayList<>();
        for (ToolState s : states.tools()) {
            if (s.pin() != null && s.pinInstall() == null) {
                missing.add(s.tool().id());
                r.warn(p.source() + " wants " + StatusCommand.pinText(s.pin()) + ", not installed",
                        StatusCommand.installCommand(s.pin()));
            }
        }
        if (missing.isEmpty() && p.warnings().isEmpty()) {
            r.ok("project " + tilde(p.dir()) + " (" + p.source() + "): " + p.tools().size()
                    + (p.tools().size() == 1 ? " pin" : " pins") + ", all installed");
        }
    }

    static void index(Report r) throws IOException {
        Path dir = r.ctx.home.index();
        Instant newest = null;
        int files = 0;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> children = Files.list(dir)) {
                for (Path f : children.filter(f -> f.toString().endsWith(".cache")).toList()) {
                    files++;
                    Instant t = Files.getLastModifiedTime(f).toInstant();
                    newest = newest == null || t.isAfter(newest) ? t : newest;
                }
            }
        }
        if (newest == null) {
            r.info("no version index cached yet; search and install fetch it");
        } else {
            r.info("version index: " + files + " cached " + (files == 1 ? "list" : "lists") + ", newest "
                    + age(Duration.between(newest, Instant.now())) + " old (refetched after 24 h)");
        }
        if (Context.offlineByEnv(r.ctx.env)) {
            r.info("offline mode is on (JKIT_NETWORK_OFFLINE): only cached version lists are used");
        }
    }

    static void javaRuns(Report r) throws Exception {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        ToolState jdk = states.get(Tool.JDK);
        Path home = jdk.current() != null ? jdk.current() : jdk.wantedHome();
        if (home == null) {
            r.info("no active JDK to try");
            return;
        }
        Path java = home.resolve("bin").resolve(r.ctx.platform.isWindows() ? "java.exe" : "java");
        Installed install = jdk.current() != null ? jdk.active() : jdk.pinInstall();
        String reinstall = install == null
                ? "check that " + tilde(home) + " holds a JDK for " + r.ctx.platform
                : "reinstall it: jkit remove jdk@" + install.version().raw() + (install.vendor() == null ? "" : " " + install.vendor().name())
                        + ", then jkit install jdk@" + install.version().raw() + (install.vendor() == null ? "" : " " + install.vendor().name());
        if (!Files.isRegularFile(java)) {
            r.fail("the active JDK " + tilde(home) + " has no " + tilde(java), reinstall);
            return;
        }
        Process process;
        try {
            process = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).start();
        } catch (IOException e) {
            r.fail("the active JDK " + tilde(home) + " can't start: " + e.getMessage()
                    + " (often a JDK for another CPU architecture)", reinstall);
            return;
        }
        process.getOutputStream().close();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            r.fail("the active JDK " + tilde(home) + " didn't finish java -version within 10 s", reinstall);
            return;
        }
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        String first = output.isEmpty() ? "(no output)" : output.lines().findFirst().orElse("");
        if (process.exitValue() == 0) {
            r.ok("active JDK runs: " + first + " (" + tilde(home) + ")");
        } else {
            r.fail("the active JDK " + tilde(home) + " fails: java -version exited " + process.exitValue() + ": " + first,
                    reinstall);
        }
    }

    static void network(Report r) {
        if (!r.network) {
            return;
        }
        // one quick try each: doctor answers "can I reach it now", not "eventually"
        JdkHttp http = new JdkHttp(r.ctx.env, Context.userAgent(), Duration.ofSeconds(10), 1);
        List<URI> uris = List.of(
                r.ctx.url("JKIT_SOURCES_FOOJAY_URL", FoojaySource.DEFAULT_BASE).resolve("distributions?include_versions=false"),
                r.ctx.url("JKIT_SOURCES_GRADLE_URL", GradleSource.DEFAULT_BASE).resolve("versions/current"),
                r.ctx.url("JKIT_SOURCES_MAVEN_URL", MavenSource.DEFAULT_BASE).resolve("org/apache/maven/apache-maven/maven-metadata.xml"),
                r.ctx.url("JKIT_SOURCES_SBT_URL", SbtSource.DEFAULT_DOWNLOADS).resolve("v1.13.1/sbt-1.13.1.zip.sha256"));
        for (URI uri : uris) {
            long start = System.nanoTime();
            try {
                http.get(uri);
                r.ok("reached " + uri.getHost() + " (" + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms)");
            } catch (Exception e) {
                r.fail("can't reach " + uri.getHost() + ": " + Errors.describe(e).getMessage(),
                        "check your connection; behind a proxy, set HTTPS_PROXY or JKIT_NETWORK_PROXY");
            }
        }
    }

    // helpers

    /** Every distinct executable named {@code name} on PATH, in PATH order (links followed). */
    static List<Path> which(Context ctx, String name) throws IOException {
        List<String> names = ctx.platform.isWindows()
                ? List.of(name + ".exe", name + ".cmd", name + ".bat", name)
                : List.of(name);
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> found = new ArrayList<>();
        for (String entry : ctx.env.getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            for (String n : names) {
                Path candidate = Path.of(entry).resolve(n);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    if (seen.add(candidate.toRealPath())) {
                        found.add(candidate);
                    }
                    break;
                }
            }
        }
        return found;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(b) && a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static long size(Path dir) throws IOException {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    private static boolean hasEntries(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            return children.findAny().isPresent();
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return bytes / 1024 + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return bytes / (1024 * 1024) + " MB";
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static String age(Duration d) {
        if (d.toMinutes() < 60) {
            return Math.max(0, d.toMinutes()) + " min";
        }
        if (d.toHours() < 48) {
            return d.toHours() + " h";
        }
        return d.toDays() + " days";
    }

    private static String useCommand(Tool tool, Installed i, boolean global) {
        return "jkit use " + tool + "@" + i.version().raw() + (i.vendor() == null ? "" : " " + i.vendor().name())
                + (i.fx() ? " --fx" : "") + (global ? " --global" : "");
    }

    private static String tilde(Path path) {
        return path == null ? "" : Changes.tilde(path.toString());
    }

    private static String tilde(String text) {
        return Changes.tilde(text);
    }

    // output

    static void print(List<Finding> findings, PrintStream out) {
        for (Finding f : findings) {
            out.println(String.format("%-6s%s", f.level().id(), dev.jkit.model.Visible.of(f.message())));
            if (f.fix() != null) {
                out.println("      fix: " + dev.jkit.model.Visible.of(f.fix()));
            }
        }
        Map<Level, Integer> counts = counts(findings);
        int warns = counts.get(Level.WARN);
        int fails = counts.get(Level.FAIL);
        out.println(warns == 0 && fails == 0 ? "no problems found"
                : plural(warns, "warning") + ", " + plural(fails, "problem"));
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static Map<Level, Integer> counts(List<Finding> findings) {
        Map<Level, Integer> counts = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            counts.put(l, 0);
        }
        findings.forEach(f -> counts.merge(f.level(), 1, Integer::sum));
        return counts;
    }

    /** {@code {schemaVersion, checks: [{id, level, message, fix}], summary: {ok, info, warn, fail}}}. */
    static Map<String, Object> json(List<Finding> findings) {
        Map<String, Object> doc = JsonOut.document();
        List<Object> checks = new ArrayList<>();
        for (Finding f : findings) {
            checks.add(JsonOut.fields("id", f.id(), "level", f.level().id(), "message", f.message(), "fix", f.fix()));
        }
        doc.put("checks", checks);
        Map<String, Object> summary = JsonOut.fields();
        counts(findings).forEach((level, n) -> summary.put(level.id(), n));
        doc.put("summary", summary);
        return doc;
    }
}
