package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.Tool;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Frees disk space: builds that {@code jkit upgrade} superseded, folders interrupted installs and
 * removals left, and cached version lists too old to be worth keeping for {@code --offline}.
 */
@Command(name = "prune", description = {"Removes what jkit no longer needs, to free disk space.",
        "It removes:",
        "- older builds within a release line once a newer one is installed (21.0.5",
        "  once 21.0.9 is in; for Maven, Gradle and sbt the line is the major version,",
        "  so 3.8.8 once 3.9.11 is in), unless global, used in this shell, pinned here,",
        "  or used by a running program (a java, a Gradle daemon); a jkit use in another",
        "  shell that runs nothing from it can't be seen",
        "- folders left by interrupted installs and removals",
        "- cached version lists older than 30 days (newer ones serve --offline)",
        "A project elsewhere that pins an exact old build gets it back with jkit sync.",
        "",
        "  jkit prune --dry-run   what would go, and how much space it frees"})
final class PruneCommand implements Callable<Integer> {
    /** Older cached lists still answer --offline and stand in during an outage; past this they are just clutter. */
    static final Duration CACHE_KEEP = Duration.ofDays(30);

    @Option(names = {"-n", "--dry-run"}, description = "Show what would be removed; remove nothing.")
    boolean dryRun;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        return run(Context.create(false, true), Path.of("").toAbsolutePath(), Instant.now());
    }

    /** One build to remove, the newer build in its line that replaces it, and the bytes it takes. */
    record Superseded(Installed install, Installed newer, long bytes) { }

    /** A running program that uses a build: its process id and command. */
    record User(long pid, String command) { }

    int run(Context ctx, Path cwd, Instant now) throws Exception {
        PrintStream say = json.json ? ctx.err : ctx.out;
        if (!Files.isDirectory(ctx.home.root())) {
            say.println("nothing to prune: " + Changes.tilde(ctx.home.root().toString()) + " doesn't exist yet");
            if (json.json) {
                ctx.out.println(JsonOut.write(document(List.of(), List.of(), List.of(), List.of(), 0, new Changes(ctx))));
            }
            return 0;
        }
        ToolStates.Result here = StatusCommand.read(ctx, cwd);
        List<Superseded> builds = new ArrayList<>(superseded(ctx, here));
        // a build a running program uses stays: deleting it under a JVM or a Gradle daemon breaks it
        Map<Path, User> inUse = inUse(ctx, builds);
        List<Superseded> kept = builds.stream().filter(s -> inUse.containsKey(s.install().home())).toList();
        builds.removeAll(kept);
        List<Object> keptRows = new ArrayList<>();
        for (Superseded s : kept) {
            User user = inUse.get(s.install().home());
            keptRows.add(JsonOut.fields("install", JsonOut.installed(ctx, s.install()), "newer", s.newer().version().raw(),
                    "bytes", s.bytes(), "pid", user.pid(), "command", user.command()));
        }
        List<Path> leftovers = leftovers(ctx);
        List<Path> cache = oldCache(ctx, now);
        long bytes = 0;
        List<Object> rows = new ArrayList<>(); // before anything is removed: the install object reads the folder
        for (Superseded s : builds) {
            bytes += s.bytes();
            rows.add(JsonOut.fields("install", JsonOut.installed(ctx, s.install()), "newer", s.newer().version().raw(),
                    "bytes", s.bytes()));
        }
        for (Path p : leftovers) {
            bytes += size(p);
        }
        for (Path p : cache) {
            bytes += size(p);
        }
        Changes report = new Changes(ctx);
        for (Superseded s : kept) {
            say.println((dryRun ? "would keep " : "kept ") + s.install().tool() + " " + s.install().label()
                    + ": " + usedBy(inUse.get(s.install().home())));
        }
        if (builds.isEmpty() && leftovers.isEmpty() && cache.isEmpty()) {
            say.println("nothing to prune");
        } else if (dryRun) {
            for (Superseded s : builds) {
                say.println("would remove " + s.install().tool() + " " + s.install().label() + " ("
                        + human(s.bytes()) + "; " + s.newer().version() + " is installed)");
            }
            for (Path p : leftovers) {
                say.println("would delete " + Changes.tilde(p.toString()) + " (left by an interrupted install or removal)");
            }
            if (!cache.isEmpty()) {
                say.println("would delete " + cache.size() + " cached version list" + (cache.size() == 1 ? "" : "s")
                        + " older than " + CACHE_KEEP.toDays() + " days");
            }
            say.println("would free " + human(bytes) + "; run without --dry-run to remove");
        } else {
            bytes = prune(ctx, cwd, builds, cache, report, say);
            report.print(say);
            say.println("freed " + human(bytes));
        }
        if (json.json) {
            ctx.out.println(JsonOut.write(document(rows, keptRows, leftovers, cache, bytes, report)));
        }
        return 0;
    }

    private static String usedBy(User user) {
        return "a running program uses it (pid " + user.pid()
                + (user.command() == null ? "" : ", " + Changes.tilde(user.command())) + ")";
    }

    /**
     * The builds a running program uses, with one such program each: its executable is in the build
     * (a java), or an argument names a file in it (a Gradle daemon's or mvn's classpath). Only what
     * the OS shows of other processes: usually the user's own.
     */
    static Map<Path, User> inUse(Context ctx, List<Superseded> builds) {
        Map<Path, User> found = new LinkedHashMap<>();
        if (builds.isEmpty()) {
            return found;
        }
        boolean windows = ctx.platform.isWindows();
        Map<Path, List<String>> folders = new LinkedHashMap<>();
        for (Superseded s : builds) {
            List<String> names = new ArrayList<>();
            Path home = s.install().home().toAbsolutePath().normalize();
            names.add(folderText(home, windows));
            try {
                names.add(folderText(home.toRealPath(), windows)); // ~/.jkit may be a link
            } catch (IOException | RuntimeException e) {
                // gone meanwhile: the written path still counts
            }
            folders.put(s.install().home(), names);
        }
        long self = ProcessHandle.current().pid();
        try (Stream<ProcessHandle> all = ProcessHandle.allProcesses()) {
            for (ProcessHandle p : (Iterable<ProcessHandle>) all::iterator) {
                if (found.size() == folders.size()) {
                    break;
                }
                if (p.pid() == self) {
                    continue;
                }
                ProcessHandle.Info info = p.info();
                List<String> words = new ArrayList<>();
                info.command().ifPresent(words::add);
                info.arguments().ifPresent(a -> words.addAll(List.of(a)));
                for (Map.Entry<Path, List<String>> f : folders.entrySet()) {
                    if (!found.containsKey(f.getKey()) && words.stream().anyMatch(w -> names(w, f.getValue(), windows))) {
                        found.put(f.getKey(), new User(p.pid(), info.command().orElse(null)));
                    }
                }
            }
        } catch (SecurityException | UnsupportedOperationException e) {
            // processes can't be listed here: nothing more is kept
        }
        return found;
    }

    /** The folder as text ending in a separator, so 21.0.5+11-temurin doesn't match 21.0.5+11-temurin-fx. */
    private static String folderText(Path folder, boolean windows) {
        String text = folder.toString();
        text = text.endsWith(java.io.File.separator) ? text : text + java.io.File.separator;
        return windows ? text.toLowerCase(java.util.Locale.ROOT) : text;
    }

    /** Whether {@code word} (a command, or an argument such as a classpath) names a file in one of {@code folders}. */
    private static boolean names(String word, List<String> folders, boolean windows) {
        String w = windows ? word.toLowerCase(java.util.Locale.ROOT).replace('/', '\\') : word;
        return folders.stream().anyMatch(w::contains);
    }

    /**
     * Removes what was found, carrying on past anything that can't be removed (a build an IDE holds
     * open on Windows) with a warning, and returns the bytes actually freed.
     */
    private long prune(Context ctx, Path cwd, List<Superseded> builds, List<Path> cache, Changes report,
            PrintStream say) throws Exception {
        long freed = 0;
        // the same locks as jkit remove: not while an install runs, nor while a link moves
        try (AutoCloseable lock = ctx.store.lock(); AutoCloseable links = ctx.store.linkLock()) {
            // decide again under the lock: a jkit use --global or an install since the scan may
            // have made one of these builds the one in use
            ToolStates.Result here = StatusCommand.read(ctx, cwd);
            java.util.Set<Path> still = new java.util.HashSet<>();
            for (Superseded s : superseded(ctx, here)) {
                still.add(s.install().home());
            }
            Map<Path, User> started = inUse(ctx, builds); // a program that started since the scan
            for (Superseded s : builds) {
                if (!still.contains(s.install().home())) {
                    continue;
                }
                if (started.containsKey(s.install().home())) {
                    say.println("kept " + s.install().tool() + " " + s.install().label() + ": "
                            + usedBy(started.get(s.install().home())));
                    continue;
                }
                try {
                    removedBuilds.addAll(RemoveCommand.remove(ctx, s.install(), here, say).json());
                    freed += s.bytes();
                } catch (dev.jkit.model.JkitException | IOException e) {
                    report.note("warning: couldn't remove " + s.install().tool() + " " + s.install().label() + " ("
                            + e.getMessage() + ")" + (ctx.platform.isWindows()
                                    ? "; something may have it open (IDE or Gradle daemon?)" : ""));
                }
            }
            // read again under the lock: an install that started meanwhile has its own folder
            for (Path p : leftovers(ctx)) {
                long size = size(p);
                try {
                    Fs.deleteRecursively(p);
                    report.folder(p, false);
                    freed += size;
                } catch (IOException e) {
                    report.note("warning: couldn't delete " + Changes.tilde(p.toString()) + " (" + e.getMessage() + ")");
                }
            }
        }
        for (Path p : cache) {
            long size = size(p);
            try {
                if (Files.deleteIfExists(p)) {
                    report.fileDeleted(p);
                    freed += size;
                }
            } catch (IOException e) {
                report.note("warning: couldn't delete " + Changes.tilde(p.toString()) + " (" + e.getMessage() + ")");
            }
        }
        return freed;
    }

    /** The changes {@link RemoveCommand#remove} reported, which it has printed already. */
    private final List<Object> removedBuilds = new ArrayList<>();

    /**
     * Every install with a newer stable build in the same release line (tool, major version,
     * vendor, JavaFX or not, as {@code jkit upgrade} groups them), except one that is global,
     * chosen in this shell, applied here from a project file, or pinned by the project here.
     * Pre-releases are left alone: an early-access build has no stable build replacing it.
     */
    static List<Superseded> superseded(Context ctx, ToolStates.Result here) throws Exception {
        Map<UpgradeCommand.Line, List<Installed>> lines = new LinkedHashMap<>();
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            for (Installed i : ctx.store.list(tool)) {
                if (!i.version().isPreRelease()) {
                    lines.computeIfAbsent(new UpgradeCommand.Line(i.tool(), i.version().part(0), i.vendor(), i.fx()),
                            k -> new ArrayList<>()).add(i);
                }
            }
        }
        List<Superseded> result = new ArrayList<>();
        for (List<Installed> line : lines.values()) {
            Installed newest = line.stream().max(Comparator.comparing(Installed::version)).orElseThrow();
            for (Installed i : line) {
                if (i != newest && !kept(ctx, i, here)) {
                    result.add(new Superseded(i, newest, size(i.home())));
                }
            }
        }
        return result;
    }

    private static boolean kept(Context ctx, Installed i, ToolStates.Result here) throws Exception {
        if (!ListCommand.notes(ctx, i).isEmpty()) {
            return true; // global or this shell's choice
        }
        for (Tool t : Tool.values()) {
            String project = ctx.env.get(ShellEnv.projectVariable(t));
            if (t.storeTool() == i.tool() && project != null && !project.isBlank()
                    && Resolve.same(Fs.path(project), i.home())) {
                return true;
            }
        }
        for (ToolState s : here.tools()) {
            if (s.pinInstall() != null && Resolve.same(s.pinInstall().home(), i.home())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folders interrupted installs and removals left: {@code tmp/install-*} and {@code tmp/remove-*},
     * and next to installs, {@code .remove-*} and {@code .*.partial} (when tools/ is on another disk).
     */
    static List<Path> leftovers(Context ctx) throws IOException {
        List<Path> found = new ArrayList<>();
        collect(ctx.home.tmp(), name -> name.startsWith("install-") || name.startsWith("remove-"), found);
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            collect(ctx.home.tools(tool), name -> name.startsWith(".remove-")
                    || name.startsWith(".") && name.endsWith(".partial"), found);
        }
        return found;
    }

    private static void collect(Path dir, java.util.function.Predicate<String> match, List<Path> into)
            throws IOException {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(p -> match.test(p.getFileName().toString())).sorted().forEach(into::add);
        }
    }

    /** Cached version lists last written more than {@link #CACHE_KEEP} ago. */
    static List<Path> oldCache(Context ctx, Instant now) throws IOException {
        List<Path> old = new ArrayList<>();
        Path index = ctx.home.index();
        if (!Files.isDirectory(index, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return old;
        }
        try (Stream<Path> files = Files.list(index)) {
            for (Path f : files.sorted().toList()) {
                if (f.getFileName().toString().endsWith(".cache") && Files.isRegularFile(f,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(f).toInstant().plus(CACHE_KEEP).isBefore(now)) {
                    old.add(f);
                }
            }
        }
        return old;
    }

    /** Bytes under {@code path}, not following links; 0 for what can't be read. */
    static long size(Path path) {
        try (Stream<Path> all = Files.walk(path)) {
            return all.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    }).sum();
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    static String human(long bytes) {
        if (bytes < 1024 * 1024) {
            return (bytes + 1023) / 1024 + " KB";
        }
        double mb = bytes / (1024.0 * 1024.0);
        return mb < 1024 ? String.format(java.util.Locale.ROOT, "%.0f MB", mb)
                : String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024);
    }

    private Map<String, Object> document(List<Object> buildRows, List<Object> keptRows, List<Path> leftovers,
            List<Path> cache, long bytes, Changes report) {
        List<Object> changes = new ArrayList<>(removedBuilds);
        changes.addAll(report.json());
        Map<String, Object> doc = JsonOut.document();
        doc.put("dryRun", dryRun);
        doc.put("builds", buildRows);
        doc.put("inUse", keptRows);
        doc.put("leftovers", leftovers);
        doc.put("cache", cache);
        doc.put("bytes", bytes);
        doc.put("changes", changes);
        doc.put("notes", report.notes());
        return doc;
    }
}
