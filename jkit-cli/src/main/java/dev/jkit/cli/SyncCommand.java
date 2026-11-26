package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.Installer;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "sync",
        description = {"Installs the project file's pins and switches this shell to them.",
                "Reads jkit.toml (or .sdkmanrc, .tool-versions, .java-version) here or above.",
                "A tool chosen in this shell with jkit use keeps winning until jkit reset.",
                "Without the shell hook it only installs; then use jkit exec, or",
                "eval \"$(jkit env)\"."})
final class SyncCommand implements Callable<Integer> {

    @Option(names = "--allow-unverified",
            description = "Install pins even when a download has no published checksum, or its publisher's signature can't be checked.")
    boolean allowUnverified;

    @Option(names = "--dry-run", description = "Only list what would be installed.")
    boolean dryRun;

    @Mixin
    Options options;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /** One pinned tool: what it resolved to and what sync did. */
    record Step(PinnedTool pin, String status, String label, Installed installed) { }

    @Override
    public Integer call() throws Exception {
        return run(options.context(), Path.of("").toAbsolutePath());
    }

    int run(Context ctx, Path cwd) throws Exception {
        ToolStates.Result states = ToolStates.read(ctx.env, cwd, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
        if (states.projectError() != null) {
            throw new JkitException(states.projectError());
        }
        if (states.project().isEmpty() && states.projectSkipped() != null) {
            throw new JkitException(states.projectSkipped(), states.projectSkipped().contains("UTF-8")
                    ? "rename the folder, or set JKIT_PROJECT_FILE to the file's absolute path"
                    : "jkit ignores project files in folders other users can change; fix the folder's owner or "
                            + "permissions, or set JKIT_PROJECT_FILE to the file's absolute path");
        }
        if (states.project().isEmpty()) {
            throw new JkitException("no jkit.toml, .sdkmanrc, .tool-versions or .java-version here or above",
                    "create one: jkit pin jdk@<version>");
        }
        ProjectSpec project = states.project().get();
        PrintStream say = json.json ? ctx.err : ctx.out;
        project.warnings().forEach(w -> ctx.err.println("warning: " + Changes.tilde(w)));
        if (!project.skipped().isEmpty()) {
            // installing the rest would leave CI on whatever version it already had for these
            String names = String.join(", ", project.skipped().stream().map(Tool::id).toList());
            throw new JkitException(project.source() + " pins " + names + ", but jkit can't read "
                    + (project.skipped().size() == 1 ? "that pin" : "those pins") + " (see the warning above); "
                    + "nothing was " + (dryRun ? "checked" : "installed"),
                    "fix the entry in " + Changes.tilde(project.dir().toString()) + ", then run jkit sync again");
        }
        say.println((dryRun ? "would sync " : "syncing ") + Changes.tilde(project.dir().toString())
                + " (" + project.source() + ")");
        if (project.tools().isEmpty()) {
            say.println("  it pins no tools");
        }

        Changes report = new Changes(ctx);
        List<Step> steps = new ArrayList<>();
        for (ToolState s : states.tools()) {
            PinnedTool pin = s.pin();
            if (pin == null) {
                continue;
            }
            Step step;
            try {
                step = sync(ctx, pin, s.pinInstall(), report);
            } catch (Exception e) {
                // what earlier pins installed stays installed: say so before the error
                report.disk();
                report.print(say);
                throw new PartialFailure(Errors.describe(e), report.json());
            }
            steps.add(step);
            say.println(StatusCommand.pinText(pin) + ": " + switch (step.status()) {
                case "already-installed" -> step.label() + " (installed)";
                case "would-install" -> "would install " + step.label();
                case "would-refuse" -> "would not install " + step.label()
                        + ": no published checksum (jkit sync --allow-unverified installs it anyway)";
                default -> "installed " + step.label();
            });
        }
        report.disk();
        if (!dryRun) {
            ProjectShell.apply(ctx, cwd, report);
        }
        report.print(say);
        if (json.json) {
            Map<String, Object> doc = JsonOut.document();
            doc.put("dryRun", dryRun);
            doc.put("project", JsonOut.fields("dir", project.dir(), "files", project.files(), "warnings", project.warnings()));
            List<Object> tools = new ArrayList<>();
            for (Step step : steps) {
                tools.add(JsonOut.fields("tool", step.pin().tool().id(), "pin", PinCommand.pinJson(step.pin()),
                        "status", step.status(), "label", step.label(),
                        "installed", step.installed() == null ? null : JsonOut.installed(ctx, step.installed())));
            }
            doc.put("tools", tools);
            doc.put("changes", report.json());
            doc.put("notes", report.notes());
            ctx.out.println(JsonOut.write(doc));
        }
        return 0;
    }

    /** Installs {@code pin} unless {@code have} already satisfies it; the download comes from the pin's vendor, else the default. */
    private Step sync(Context ctx, PinnedTool pin, Installed have, Changes report) throws Exception {
        if (have != null) {
            return new Step(pin, "already-installed", have.label(), have);
        }
        String vendorName = pin.vendor() == null ? null : pin.vendor().name();
        boolean pre = pin.version() != null && Version.parse(pin.version()).isPreRelease();
        Artifact artifact = InstallCommand.artifact(ctx, pin.spec(), Resolve.knownVendor(ctx, pin.spec(), vendorName),
                pin.fx(), pre);
        if (dryRun) {
            // the real sync refuses a download it can't check; offline, the checksum isn't looked up.
            // One with a signature jkit can check (Corretto) is checked by that instead
            boolean refused = artifact.checksum() == null && InstallCommand.signer(artifact) == null
                    && !allowUnverified && !ctx.http().offline();
            return new Step(pin, refused ? "would-refuse" : "would-install", artifact.label(), null);
        }
        Installer.Result result = InstallCommand.download(ctx, artifact, allowUnverified, "jkit sync --allow-unverified");
        report.disk();
        for (Path leftover : result.removedLeftovers()) {
            report.folder(leftover, false);
        }
        if (result.fresh()) {
            report.folder(result.installed().home(), true);
        }
        return new Step(pin, "installed", artifact.label(), result.installed());
    }
}
