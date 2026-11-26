package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ProjectWriter;
import dev.jkit.core.TextFiles;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "pin",
        description = {"Records a version in the project's jkit.toml.",
                "Creates the file if needed.",
                "Edits the jkit.toml found here or above, or with --here the one in this folder;",
                "only that tool's line changes. The version must be installed or downloadable.",
                "Without a version, pins the one this shell uses (for jdk and graalvm, its",
                "feature release: 21).",
                "",
                "  jkit pin jdk@21          any vendor, newest installed 21",
                "  jkit pin jdk@21 zulu --exact",
                "  jkit pin maven"})
final class PinCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class,
            completionCandidates = Candidates.Tools.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<vendor>", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--fx", description = "Require a JavaFX build.")
    boolean fx;

    @Option(names = "--exact", description = "Write the full version and vendor of the build it resolves to.")
    boolean exact;

    @Option(names = "--here", description = "Use jkit.toml in this folder, even if a project file is further up.")
    boolean here;

    @Mixin
    Options options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    /** {@code jdk@latest}: pin "latest" (the newest), not the version active here like a bare {@code jdk}. */
    boolean latest;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /** What to write, and the build that showed the version exists: an install, or a download. */
    record Choice(PinnedTool pin, Installed installed, Artifact available) { }

    @Override
    public Integer call() throws Exception {
        latest = commandSpec.commandLine().getParseResult().matchedPositional(0).originalStringValues().stream()
                .anyMatch(ToolSpec::saysLatest);
        return run(options.context(), Path.of("").toAbsolutePath());
    }

    int run(Context ctx, Path cwd) throws Exception {
        // the vendor's own newest LTS: the one typed, else the default, which sync installs
        spec = Resolve.lts(ctx, spec, true, spec != null && spec.isLts() ? Resolve.knownVendor(ctx, spec, vendorText) : null, fx);
        String retry = "jkit pin " + spec + (latest ? "@latest" : "") + (vendorText == null ? "" : " " + vendorText) + (fx ? " --fx" : "")
                + (exact ? " --exact" : "");
        Path file = ProjectShell.target(ctx, cwd, here, retry);
        Choice choice = choose(ctx, cwd);
        PinnedTool pin = choice.pin();

        boolean created = !Files.exists(file);
        PinnedTool previous = created ? null : ProjectFile.read(file).get(pin.tool());
        PrintStream say = json.json ? ctx.err : ctx.out;
        Changes report = new Changes(ctx).disk();
        String name = Changes.tilde(file.toString());
        if (pin.equals(previous)) {
            say.println(name + " already pins " + StatusCommand.pinText(pin));
        } else {
            String text = created ? ProjectWriter.fresh(pin)
                    : ProjectWriter.set(file, TextFiles.text(file), pin).text();
            ProjectShell.write(file, text);
            report.file(file, created);
            say.println(previous == null
                    ? "pinned " + StatusCommand.pinText(pin) + " in " + name
                    : "pinned " + StatusCommand.pinText(pin) + " in " + name + " (was " + StatusCommand.pinText(previous) + ")");
        }
        ProjectShell.apply(ctx, cwd, report);
        report.print(say);
        if (json.json) {
            Map<String, Object> doc = JsonOut.document();
            doc.put("file", file);
            doc.put("created", created);
            doc.put("tool", pin.tool().id());
            doc.put("pin", pinJson(pin));
            doc.put("previous", previous == null ? null : pinJson(previous));
            doc.put("installed", choice.installed() == null ? null : JsonOut.installed(ctx, choice.installed()));
            doc.put("changes", report.json());
            doc.put("notes", report.notes());
            ctx.out.println(JsonOut.write(doc));
        }
        return 0;
    }

    static Map<String, Object> pinJson(PinnedTool pin) {
        return JsonOut.fields("version", pin.version(), "vendor", pin.vendor() == null ? null : pin.vendor().name(),
                "fx", pin.fx());
    }

    /**
     * The pin to write. The version must match an install or something the tool's source offers,
     * so a typo fails here and not on a teammate's machine.
     */
    Choice choose(Context ctx, Path cwd) throws Exception {
        Tool tool = spec.tool();
        Vendor vendor = vendorText == null ? null : Resolve.vendor(ctx, spec, vendorText);
        if (fx && !tool.hasVendors()) {
            throw new JkitException(tool + " has no JavaFX builds; drop --fx");
        }
        String version = spec.version();
        if (version == null && !latest) {
            Installed current = current(ctx, cwd, tool);
            if (current == null) {
                throw new JkitException("no " + tool + " is active here to pin",
                        "name a version: jkit pin " + tool.id() + "@<version>");
            }
            version = exact || !tool.hasVendors() ? current.version().raw()
                    : String.valueOf(current.major());
        }
        PinnedTool wanted = new PinnedTool(tool, version, vendor, fx);
        Installed installed = ToolStates.resolve(ctx.store, ctx.config, wanted).orElse(null);
        if (installed != null) {
            return new Choice(exact ? exactPin(tool, installed.version(), installed.vendor(), installed.fx()) : wanted,
                    installed, null);
        }
        Vendor source = Resolve.knownVendor(ctx, wanted.spec(), vendorText);
        Artifact artifact = InstallCommand.artifact(ctx, wanted.spec(), source, fx,
                version != null && Version.parse(version).isPreRelease());
        return new Choice(exact ? exactPin(tool, artifact.version(), artifact.vendor(), artifact.fx()) : wanted,
                null, artifact);
    }

    private static PinnedTool exactPin(Tool tool, Version version, Vendor vendor, boolean fx) {
        return new PinnedTool(tool, version.raw(), tool.hasVendors() ? vendor : null, fx);
    }

    /** The install this shell uses for {@code tool} ({@code *_HOME}), else the one it would get. */
    private static Installed current(Context ctx, Path cwd, Tool tool) throws Exception {
        ToolState state = ToolStates.read(ctx.env, cwd, ctx.home, ctx.store, ctx.config, ctx.os::readLink).get(tool);
        if (state.active() != null) {
            return state.active();
        }
        return ProjectShell.installAt(ctx, tool, state.wantedHome());
    }
}
