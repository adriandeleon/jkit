package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.Installer;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.sources.Artifact;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Brings each installed release line up to its newest build: JDK 21 temurin 21.0.5 → 21.0.9,
 * Maven 3.9.9 → 3.9.11, Gradle 8.10 → 8.14.3. Never crosses a major version. Older builds stay
 * installed; a global link that pointed at the line's newest build moves to the new one.
 */
@Command(name = "upgrade",
        description = {"Installs the newest build of each installed major version.",
                "A global default that pointed at the old build moves to the new one. Old builds stay",
                "(jkit remove them).",
                "",
                "  jkit upgrade --dry-run           what would change (21.0.5 → 21.0.9, maven 3.9.11 → 3.10.2)",
                "  jkit upgrade jdk"})
final class UpgradeCommand implements Callable<Integer> {

    /** One release line: a store tool, a major version and (for JDKs) a vendor and JavaFX or not. */
    record Line(Tool tool, int major, Vendor vendor, boolean fx) {
        /** The tool as the user names it: graalvm for GraalVM-based JDKs. */
        Tool userTool() {
            return tool == Tool.JDK && vendor != null && vendor.isGraalVmBased() ? Tool.GRAALVM : tool;
        }

        @Override
        public String toString() {
            return userTool() + "@" + major + (vendor == null ? "" : " " + vendor.name()) + (fx ? " --fx" : "");
        }
    }

    @Parameters(index = "0", arity = "0..1", paramLabel = "[tool[@major]]", converter = ToolSpecConverter.class,
            completionCandidates = Candidates.Tools.class, description = "Only this tool (and major). Default: everything.")
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<vendor>", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--fx", description = "Treat JDKs as JavaFX builds. Only needed for installs made before jkit recorded JavaFX.")
    boolean fx;

    @Option(names = "--allow-unverified",
            description = "Install upgrades even when the source publishes no checksum for the download.")
    boolean allowUnverified;

    @Option(names = "--pre", description = "Allow pre-releases.")
    boolean pre;

    @Option(names = {"-n", "--dry-run"}, description = "Show what would be upgraded; install nothing.")
    boolean dryRun;

    @Mixin
    JsonOut.Flag jsonFlag = new JsonOut.Flag();

    @Mixin
    Options options;

    @Override
    public Integer call() throws Exception {
        Context ctx = options.context();
        spec = Resolve.lts(ctx, spec, vendorText, fx);
        Map<Line, List<Installed>> lines = lines(ctx);
        boolean json = jsonFlag.json;
        java.io.PrintStream say = json ? ctx.err : ctx.out;
        if (lines.isEmpty() && !json) {
            ctx.out.println(spec == null ? "nothing installed yet · try: jkit search jdk"
                    : "no installed " + spec + (vendorText == null ? "" : " " + vendorText) + " to upgrade");
            return 0;
        }
        lists = new Changes(ctx);
        List<Object> rows = new ArrayList<>();
        int failures = 0;
        int available = 0;
        for (Map.Entry<Line, List<Installed>> entry : lines.entrySet()) {
            Map<String, Object> row = upgrade(ctx, say, entry.getKey(), entry.getValue().getFirst());
            rows.add(row);
            if ("failed".equals(row.get("status"))) {
                failures++;
            } else if ("available".equals(row.get("status"))) {
                available++;
            }
        }
        // version lists fetched to check the lines (the upgrades report their own changes)
        lists.disk();
        if (!lists.isEmpty()) {
            say.println("checked for newer builds");
            lists.print(say);
        }
        if (json) {
            Map<String, Object> doc = JsonOut.document();
            doc.put("dryRun", dryRun);
            doc.put("lines", rows);
            doc.put("changes", lists.json());
            ctx.out.println(JsonOut.write(doc));
        } else if (dryRun && available > 0) {
            ctx.out.println("run without --dry-run to install");
        }
        return failures == 0 ? 0 : 1;
    }

    /**
     * One release line, for text and {@code --json} alike: {@code status} up-to-date, available
     * (dry run), upgraded or failed, the installed {@code from} build, the newest {@code to} build,
     * and on failure an {@code error}. A failure on one line never stops the others, so what
     * earlier lines changed is always reported.
     */
    /** The cached files written to check the lines: the top-level {@code changes}. */
    private Changes lists;

    private Map<String, Object> upgrade(Context ctx, java.io.PrintStream say, Line line, Installed newest) {
        Map<String, Object> row = JsonOut.fields(
                "tool", line.userTool().id(),
                "major", line.major(),
                "vendor", line.vendor() == null ? null : line.vendor().name(),
                "fx", line.fx(),
                "from", newest.version().raw(),
                "to", null,
                "status", null);
        try {
            Artifact artifact = resolve(ctx, line);
            lists.disk(); // the lists fetched so far go to the top level, not to this line's install
            row.put("to", artifact.version().raw());
            if (artifact.version().compareTo(newest.version()) <= 0
                    || ctx.store.contains(line.tool(), artifact.version(), artifact.vendor(), artifact.fx())) {
                row.put("to", newest.version().raw());
                row.put("status", "up-to-date");
                say.println(line + " is up to date (" + newest.version() + ")");
                return row;
            }
            if (dryRun) {
                row.put("status", "available");
                say.println(line + ": " + newest.version() + " → " + artifact.version());
                return row;
            }
            Installer.Result result = InstallCommand.download(ctx, artifact, allowUnverified,
                    "jkit upgrade " + line.userTool() + " --allow-unverified");
            row.put("status", "upgraded");
            row.put("verified", result.verified());
            row.put("path", result.installed().home());
            Changes changes = InstallCommand.installed(ctx, result);
            try {
                moveLinks(ctx, changes, line, newest, result.installed().home());
            } catch (Exception e) {
                // the new build is installed either way: say so, and what is still to do
                JkitException je = Errors.describe(e);
                changes.note("warning: couldn't move the global link to the new build: "
                        + dev.jkit.model.Visible.of(je.getMessage()) + "; to do it yourself: jkit use " + line.userTool()
                        + "@" + artifact.version() + (line.vendor() == null ? "" : " " + line.vendor().name())
                        + (line.fx() ? " --fx" : "") + " --global");
            }
            changes.note("the old build stays: jkit remove " + line.userTool() + "@" + newest.version()
                    + (line.vendor() == null ? "" : " " + line.vendor().name()) + (line.fx() ? " --fx" : ""));
            row.put("changes", changes.json());
            say.println("upgraded " + line + ": " + newest.version() + " → " + artifact.version());
            changes.print(say);
        } catch (Exception e) {
            JkitException je = Errors.describe(e);
            row.put("status", "failed");
            row.put("error", JsonOut.fields("message", je.getMessage(), "hints", List.copyOf(je.hints())));
            ctx.err.println("warning: " + line + ": " + dev.jkit.model.Visible.of(je.getMessage()));
            je.hints().forEach(h -> ctx.err.println("  " + h));
        }
        return row;
    }

    /** Installed builds grouped by release line, each newest first, lines in store order. */
    Map<Line, List<Installed>> lines(Context ctx) throws Exception {
        List<Tool> tools = spec == null ? List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT) : List.of(spec.tool());
        Vendor vendor = spec == null
                ? (vendorText == null ? null : Vendor.of(vendorText))
                : (vendorText == null ? null : Resolve.vendor(ctx, spec, vendorText));
        Map<Line, List<Installed>> lines = new LinkedHashMap<>();
        for (Tool tool : tools) {
            for (Installed i : ctx.store.list(tool)) {
                if (vendor != null && !vendor.equals(i.vendor())) {
                    continue;
                }
                if (spec != null && spec.version() != null && i.version().part(0) != spec.major()) {
                    continue;
                }
                if (i.version().isPreRelease() && !pre) {
                    continue; // an early-access line has no GA build to move to
                }
                lines.computeIfAbsent(new Line(i.tool(), i.version().part(0), i.vendor(),
                        i.tool() == Tool.JDK && (i.fx() || fx)), k -> new ArrayList<>())
                        .add(i);
            }
        }
        return lines;
    }

    private Artifact resolve(Context ctx, Line line) throws Exception {
        String major = Integer.toString(line.major());
        return switch (line.tool()) {
            case JDK, GRAALVM -> ctx.foojay().resolve(line.userTool(), major, line.vendor(), ctx.platform, line.fx(), pre);
            case MAVEN -> ctx.maven().resolve(major, pre);
            case GRADLE -> ctx.gradle().resolve(major, pre);
            case SBT -> ctx.sbt().resolve(major, pre);
        };
    }

    /** Moves global links that pointed at the line's previous newest build, recording them; hints for this shell. */
    private static void moveLinks(Context ctx, Changes changes, Line line, Installed previous, Path newHome) throws Exception {
        for (Tool t : Tool.values()) {
            if (t.storeTool() != line.tool()) {
                continue;
            }
            Path link = ctx.home.global(t);
            // read and moved under the store lock: a jkit use --global in between must not be undone
            boolean moved = false;
            try (AutoCloseable lock = ctx.store.lock()) {
                Path target = ctx.os.readLink(link);
                if (target != null && Resolve.same(target, previous.home())
                        && java.nio.file.Files.isRegularFile(newHome.resolve(dev.jkit.core.Store.MARKER), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    ctx.os.pointLink(link, newHome);
                    moved = true;
                }
            }
            if (moved) {
                changes.link(link, newHome);
            }
            String shell = ctx.env.get(ShellEnv.stateVariable(t));
            if (shell != null && Resolve.same(dev.jkit.core.Fs.path(shell), previous.home())) {
                changes.note("this shell still uses the old build: jkit use " + t + "@" + line.major()
                        + (line.vendor() == null ? "" : " " + line.vendor().name()) + (line.fx() ? " --fx" : ""));
            }
        }
    }
}
