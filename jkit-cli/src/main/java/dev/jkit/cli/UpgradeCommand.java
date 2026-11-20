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
        description = "Installs the newest build within each installed major version and moves --global to it.")
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

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--fx", description = "Treat JDKs as JavaFX builds. Only needed for installs made before jkit recorded JavaFX.")
    boolean fx;

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
        Map<Line, List<Installed>> lines = lines(ctx);
        if (jsonFlag.json) {
            return json(ctx, lines);
        }
        if (lines.isEmpty()) {
            ctx.out.println(spec == null ? "nothing installed yet · try: jkit search jdk"
                    : "no installed " + spec + (vendorText == null ? "" : " " + vendorText) + " to upgrade");
            return 0;
        }
        int failures = 0;
        int upgraded = 0;
        for (Map.Entry<Line, List<Installed>> entry : lines.entrySet()) {
            Line line = entry.getKey();
            Installed newest = entry.getValue().getFirst();
            Artifact artifact;
            try {
                artifact = resolve(ctx, line);
            } catch (JkitException e) {
                ctx.err.println("warning: " + line + ": " + e.getMessage());
                failures++;
                continue;
            }
            if (artifact.version().compareTo(newest.version()) <= 0
                    || ctx.store.contains(line.tool(), artifact.version(), artifact.vendor(), artifact.fx())) {
                ctx.out.println(line + " is up to date (" + newest.version() + ")");
                continue;
            }
            if (dryRun) {
                ctx.out.println(line + ": " + newest.version() + " → " + artifact.version());
                upgraded++;
                continue;
            }
            ctx.err.println("downloading " + artifact.label() + " from " + artifact.url().getHost());
            Installer.Result result = ctx.installer().install(artifact, new ProgressBar(ctx.err));
            if (!result.verified()) {
                ctx.err.println("warning: the source publishes no checksum; the download was not verified");
            }
            ctx.out.println("upgraded " + line + ": " + newest.version() + " → " + artifact.version());
            upgraded++;
            moveLinks(ctx, ctx.out, line, newest, result.installed().home());
            ctx.out.println("  remove the old build: jkit remove " + line.userTool() + "@" + newest.version()
                    + (line.vendor() == null ? "" : " " + line.vendor().name()) + (line.fx() ? " --fx" : ""));
        }
        if (dryRun && upgraded > 0) {
            ctx.out.println("run without --dry-run to install");
        }
        return failures == 0 ? 0 : 1;
    }

    /**
     * {@code dryRun} and {@code lines}: one object per release line with {@code status} up-to-date,
     * available (dry run), upgraded or failed, the installed {@code from} build, the newest {@code to}
     * build, and on failure an {@code error}. Exit code 1 when any line failed.
     */
    private int json(Context ctx, Map<Line, List<Installed>> lines) throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("dryRun", dryRun);
        List<Object> out = new ArrayList<>();
        int failures = 0;
        for (Map.Entry<Line, List<Installed>> entry : lines.entrySet()) {
            Line line = entry.getKey();
            Installed newest = entry.getValue().getFirst();
            Map<String, Object> row = JsonOut.fields(
                    "tool", line.userTool().id(),
                    "major", line.major(),
                    "vendor", line.vendor() == null ? null : line.vendor().name(),
                    "fx", line.fx(),
                    "from", newest.version().raw(),
                    "to", null,
                    "status", null);
            out.add(row);
            Artifact artifact;
            try {
                artifact = resolve(ctx, line);
            } catch (JkitException e) {
                row.put("status", "failed");
                row.put("error", JsonOut.fields("message", e.getMessage(), "hints", List.copyOf(e.hints())));
                failures++;
                continue;
            }
            row.put("to", artifact.version().raw());
            if (artifact.version().compareTo(newest.version()) <= 0
                    || ctx.store.contains(line.tool(), artifact.version(), artifact.vendor(), artifact.fx())) {
                row.put("to", newest.version().raw());
                row.put("status", "up-to-date");
                continue;
            }
            if (dryRun) {
                row.put("status", "available");
                continue;
            }
            ctx.err.println("downloading " + artifact.label() + " from " + artifact.url().getHost());
            try {
                Installer.Result result = ctx.installer().install(artifact, new ProgressBar(ctx.err));
                row.put("status", "upgraded");
                row.put("verified", result.verified());
                row.put("path", result.installed().home());
                moveLinks(ctx, ctx.err, line, newest, result.installed().home());
            } catch (Exception e) {
                JkitException je = Errors.describe(e);
                row.put("status", "failed");
                row.put("error", JsonOut.fields("message", je.getMessage(), "hints", List.copyOf(je.hints())));
                failures++;
            }
        }
        doc.put("lines", out);
        ctx.out.println(JsonOut.write(doc));
        return failures == 0 ? 0 : 1;
    }

    /** Installed builds grouped by release line, each newest first, lines in store order. */
    Map<Line, List<Installed>> lines(Context ctx) throws Exception {
        List<Tool> tools = spec == null ? List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE) : List.of(spec.tool());
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
        };
    }

    /** Moves global links that pointed at the line's previous newest build; hints for this shell. */
    private static void moveLinks(Context ctx, java.io.PrintStream out, Line line, Installed previous, Path newHome) throws Exception {
        for (Tool t : Tool.values()) {
            if (t.storeTool() != line.tool()) {
                continue;
            }
            Path link = ctx.home.global(t);
            Path target = ctx.os.readLink(link);
            if (target != null && target.equals(previous.home())) {
                ctx.os.pointLink(link, newHome);
                out.println("  global " + t + " → " + line.userTool() + " " + newHome.getFileName());
            }
            String shell = ctx.env.get(ShellEnv.stateVariable(t));
            if (shell != null && Path.of(shell).equals(previous.home())) {
                out.println("  this shell still uses the old build: jkit use " + t + "@" + line.major()
                        + (line.vendor() == null ? "" : " " + line.vendor().name()) + (line.fx() ? " --fx" : ""));
            }
        }
    }
}
