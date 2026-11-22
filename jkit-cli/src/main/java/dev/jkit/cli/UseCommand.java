package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "use", description = "Activates an installed version for this shell, or globally with --global.")
final class UseCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--global", description = "Default for new shells and apps.")
    boolean global;

    @Option(names = "--fx", description = "Use a JavaFX build. Without it, plain builds win when both are installed.")
    boolean fx;

    @picocli.CommandLine.Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        Vendor vendor = Resolve.vendor(ctx, spec, vendorText);
        Installed chosen = Resolve.installed(ctx, spec, vendor, vendorText, fx).getFirst();
        Tool tool = spec.tool();
        if (global) {
            ctx.os.pointLink(ctx.home.global(tool), chosen.home());
            Changes changes = new Changes(ctx).link(ctx.home.global(tool), chosen.home()).globalEffect(tool);
            PinnedTool pin = projectPin(ctx, tool);
            if (pin != null) {
                changes.note(projectFile(ctx) + " here pins " + StatusCommand.pinText(pin)
                        + ", which wins over the global " + tool + " in this project");
            }
            PrintStream say = json.json ? ctx.err : ctx.out;
            say.println("global " + tool + " → " + chosen.label());
            changes.print(say);
            printJson(ctx, "global", chosen, changes);
            return 0;
        }
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active, so this shell can't be changed",
                    "load it in new shells: jkit hook bash --install (or zsh, fish)",
                    "or set the default: jkit use " + spec + (vendorText == null ? "" : " " + vendorText) + " --global",
                    "or run one command with it: jkit exec " + spec + (vendorText == null ? "" : " " + vendorText) + " -- <command>");
        }
        boolean graalVm = chosen.vendor() != null && chosen.vendor().isGraalVmBased();
        Map<String, String> changes = ShellEnv.use(ctx.env, ctx.home, tool, chosen.home(), graalVm);
        Files.writeString(Path.of(envFile), ShellEnv.format(changes, ctx.env.getOrDefault("JKIT_SHELL", "bash")));
        Changes report = new Changes(ctx).env(changes)
                .note("only this shell; for new shells too: jkit use " + tool + "@" + chosen.version().raw()
                        + (chosen.vendor() == null ? "" : " " + chosen.vendor().name()) + (chosen.fx() ? " --fx" : "") + " --global");
        PinnedTool pin = projectPin(ctx, tool);
        if (pin != null) {
            report.note("overrides " + projectFile(ctx) + "'s " + StatusCommand.pinText(pin)
                    + " in this shell; jkit reset " + tool + " to go back to it");
        }
        PrintStream say = json.json ? ctx.err : ctx.out;
        say.println("using " + tool + " " + chosen.label() + " in this shell");
        report.print(say);
        printJson(ctx, "shell", chosen, report);
        return 0;
    }

    /** The project file found here pins {@code tool}: that pin, else null. A broken file counts as none. */
    private static PinnedTool projectPin(Context ctx, Tool tool) {
        try {
            return ProjectFile.find(Path.of("").toAbsolutePath(), ctx.env).map(p -> p.get(tool)).orElse(null);
        } catch (JkitException e) {
            return null;
        }
    }

    private static String projectFile(Context ctx) {
        try {
            return ProjectFile.find(Path.of("").toAbsolutePath(), ctx.env).map(p -> p.source()).orElse("the project file");
        } catch (JkitException e) {
            return "the project file";
        }
    }

    /** {@code {schemaVersion, scope: global|shell, tool, installed: {…}, changes: […], notes: […]}}. */
    private void printJson(Context ctx, String scope, Installed chosen, Changes changes) throws Exception {
        if (!json.json) {
            return;
        }
        java.util.Map<String, Object> doc = JsonOut.document();
        doc.put("scope", scope);
        doc.put("tool", spec.tool().id());
        doc.put("installed", JsonOut.installed(ctx, chosen));
        doc.put("changes", changes.json());
        doc.put("notes", changes.notes());
        ctx.out.println(JsonOut.write(doc));
    }
}
