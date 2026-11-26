package dev.jkit.cli;

import dev.jkit.core.AtomicFile;
import dev.jkit.core.Config;
import dev.jkit.core.Installed;
import dev.jkit.core.ShellRc;
import dev.jkit.core.Store;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.core.WorkDirs;
import dev.jkit.model.JkitException;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Doctor's checks of jkit itself: its home and config, the installs, the project file, the cached
 * version lists and whether the active JDK runs.
 */
final class DoctorInstallChecks {
    private DoctorInstallChecks() { }

    static void home(DoctorReport r) {
        Path root = r.ctx.home.root();
        r.ok("jkit " + VersionProvider.version() + ", " + r.ctx.platform + ", home " + r.tilde(root));
        if (!Files.isDirectory(root)) {
            r.info(r.tilde(root) + " doesn't exist yet; the first install creates it");
        } else if (!Files.isWritable(root)) {
            r.fail("can't write to " + r.tilde(root) + ", so installs and jkit use --global will fail",
                    "check its owner and permissions: " + (DoctorReport.WINDOWS ? "Get-Acl " : "ls -ld ") + r.arg(root));
        }
    }

    static void config(DoctorReport r) throws IOException {
        Path file = r.ctx.home.config();
        if (r.ctx.configError != null) {
            r.fail(r.ctx.configError, "fix " + r.tilde(file) + ", or move it away to use the defaults");
            return;
        }
        List<String> problems = new ArrayList<>();
        // no cache yet: nothing to check against, and opening it would create the jkit home, which
        // doctor never does (it changes nothing)
        boolean checked = Files.isDirectory(r.ctx.home.index());
        for (Tool tool : checked ? List.of(Tool.JDK, Tool.GRAALVM) : List.<Tool>of()) {
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
            r.warn(problem, "set jdk.vendor / graalvm.vendor in " + r.tilde(file) + ", or JKIT_JDK_VENDOR");
        }
        if (problems.isEmpty()) {
            // where each comes from, as jkit config says: config.toml may hold other settings only
            Map<Config.Setting, String> from = new EnumMap<>(Config.Setting.class);
            for (ConfigCommand.Effective e : ConfigCommand.effective(r.ctx)) {
                from.put(e.setting(), switch (e.source()) {
                    case "env" -> "from " + e.setting().env;
                    case "file" -> "from " + r.tilde(file);
                    default -> "default";
                });
            }
            r.ok("default vendors: " + r.ctx.config.defaultVendor(Tool.JDK) + " (jdk, "
                    + from.get(Config.Setting.JDK_VENDOR) + "), "
                    + r.ctx.config.defaultVendor(Tool.GRAALVM) + " (graalvm, "
                    + from.get(Config.Setting.GRAALVM_VENDOR) + ")"
                    + (checked ? "" : "; not checked against foojay: no cached vendor list yet"));
        }
    }

    static void store(DoctorReport r) throws IOException {
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
                    // a .name.partial folder is a copy from another disk that never finished, marker or not
                    boolean partial = child.getFileName().toString().startsWith(".");
                    if (Files.isDirectory(child) && !Files.isSymbolicLink(child) && (partial
                            || !Files.isRegularFile(child.resolve(Store.MARKER), LinkOption.NOFOLLOW_LINKS))) {
                        any = true;
                        r.warn("unfinished install (" + (partial ? "a copy that never finished" : "no " + Store.MARKER)
                                + "): " + r.tilde(child),
                                "remove it: " + r.removeAll(r.arg(child)));
                    }
                }
            }
        }
        Path tmp = r.ctx.home.tmp();
        List<Path> left = leftIn(tmp);
        if (!left.isEmpty()) {
            any = true;
            long bytes = 0;
            for (Path p : left) {
                bytes += size(p);
            }
            r.warn(human(bytes) + " left in " + r.tilde(tmp) + " by interrupted downloads",
                    "when no jkit install is running: " + r.removeAll(r.arg(tmp) + File.separator + "*")); // the * outside any quotes
        }
        if (!any) {
            r.ok(installs + (installs == 1 ? " install" : " installs") + ", nothing left over");
        }
    }

    static void project(DoctorReport r) throws IOException {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        if (states.projectError() != null) {
            r.fail(states.projectError(), "fix the file, or set JKIT_PROJECT_FILE= (empty) to ignore project files");
            return;
        }
        if (states.project().isEmpty() && states.projectSkipped() != null) {
            r.warn(r.tilde(states.projectSkipped()), states.projectSkipped().contains("UTF-8")
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
            r.warn(r.tilde(warning), null);
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
            r.ok("project " + r.tilde(p.dir()) + " (" + p.source() + "): " + p.tools().size()
                    + (p.tools().size() == 1 ? " pin" : " pins") + ", all installed");
        }
    }

    /**
     * Temporary files a write of a startup file, config.toml or jkit.toml left when jkit was
     * killed halfway (the file itself was never touched). Silent when there are none.
     */
    static void tempFiles(DoctorReport r) {
        Set<Path> dirs = new LinkedHashSet<>();
        try {
            Path userHome = HookCommand.userHome(r.ctx);
            for (String shell : List.of("bash", "zsh", "fish")) {
                dirs.add(ShellRc.file(shell, r.ctx.env, userHome).getParent());
            }
        } catch (JkitException noHome) {
            // no startup files to look next to
        }
        dirs.add(r.ctx.home.config().getParent());
        ToolStates.Result states = r.states;
        if (states != null && states.project().isPresent()) {
            dirs.add(states.project().get().dir());
        }
        dirs.add(r.cwd);
        for (Path dir : dirs) {
            for (Path left : AtomicFile.leftovers(dir)) {
                r.warn("a temporary file an interrupted jkit write left: " + r.tilde(left),
                        "when no jkit command is running: " + (DoctorReport.WINDOWS ? "Remove-Item " : "rm ") + r.arg(left));
            }
        }
    }

    static void index(DoctorReport r) throws IOException {
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

    static void javaRuns(DoctorReport r) throws Exception {
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
                ? "check that " + r.tilde(home) + " holds a JDK for " + r.ctx.platform
                : "reinstall it: jkit remove jdk@" + install.version().raw() + (install.vendor() == null ? "" : " " + install.vendor().name())
                        + ", then jkit install jdk@" + install.version().raw() + (install.vendor() == null ? "" : " " + install.vendor().name());
        if (!Files.isRegularFile(java)) {
            r.fail("the active JDK " + r.tilde(home) + " has no " + r.tilde(java), reinstall);
            return;
        }
        // to a file, not a pipe: a pipe that fills up stalls the JDK, and one a child process keeps
        // open would hang the read after java has exited
        Path log = Files.createTempFile("jkit-java-version", ".txt");
        String output;
        Process process;
        try {
            try {
                process = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true)
                        .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE).start();
            } catch (IOException e) {
                r.fail("the active JDK " + r.tilde(home) + " can't start: " + e.getMessage()
                        + " (often a JDK for another CPU architecture)", reinstall);
                return;
            }
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                r.fail("the active JDK " + r.tilde(home) + " didn't finish java -version within 10 s", reinstall);
                return;
            }
            try (InputStream in = Files.newInputStream(log)) {
                output = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8).strip();
            }
        } finally {
            try {
                Files.deleteIfExists(log);
            } catch (IOException e) {
                // Windows: a child of java may still hold it; the temp folder's cleanup gets it
            }
        }
        String first = output.isEmpty() ? "(no output)" : output.lines().findFirst().orElse("");
        if (process.exitValue() == 0) {
            r.ok("active JDK runs: " + first + " (" + r.tilde(home) + ")");
        } else {
            r.fail("the active JDK " + r.tilde(home) + " fails: java -version exited " + process.exitValue() + ": " + first,
                    reinstall);
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

    /** What is in tmp/, but the folders installs running right now are downloading into. */
    private static List<Path> leftIn(Path tmp) throws IOException {
        if (!Files.isDirectory(tmp)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(tmp)) {
            return children.filter(p -> !WorkDirs.inUse(p)).toList();
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
}
