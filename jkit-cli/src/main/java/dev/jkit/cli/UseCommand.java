package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ProjectFile;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "use", description = {"Activates an installed version for this shell, or with --global.",
        "This shell needs the jkit hook (jkit hook --install); --global works without it.",
        "",
        "  jkit use jdk@21 --global         the default for new shells",
        "  jkit use jdk@17 zulu             only this shell",
        "  jkit reset jdk                   back to the project's or the global version"})
final class UseCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<vendor>", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--global", description = "Default for new shells and apps.")
    boolean global;

    @Option(names = "--fx", description = "Use a JavaFX build. Without it, plain builds win when both are installed.")
    boolean fx;

    @CommandLine.Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        return run(Context.local());
    }

    int run(Context ctx) throws Exception {
        spec = Resolve.lts(ctx, spec, vendorText, fx);
        Vendor vendor = Resolve.vendor(ctx, spec, vendorText);
        Installed chosen = Resolve.installed(ctx, spec, vendor, vendorText, fx).getFirst();
        Tool tool = spec.tool();
        if (global) {
            Path link = ctx.home.global(tool);
            Path target = ctx.os.readLink(link);
            boolean already = target != null && Fs.same(target, chosen.home());
            Changes changes = new Changes(ctx);
            if (!already) {
                Resolve.pointGlobal(ctx, link, chosen.home());
                changes.link(link, chosen.home());
            }
            changes.globalEffect(tool);
            PinnedTool pin = projectPin(ctx, tool);
            if (pin != null) {
                changes.note(projectFile(ctx, tool) + " here pins " + StatusCommand.pinText(pin)
                        + ", which wins over the global " + tool + " in this project");
            }
            PrintStream say = json.json ? ctx.err : ctx.out;
            say.println(already ? "the global " + tool + " is already " + chosen.label() + ", unchanged"
                    : "global " + tool + " → " + chosen.label());
            changes.print(say);
            printJson(ctx, "global", chosen, changes, !already);
            return 0;
        }
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active, so this shell can't be changed",
                    Changes.hookHint(ctx),
                    "or set the default: jkit use " + spec + (vendorText == null ? "" : " " + vendorText) + " --global",
                    "or run one command with it: jkit exec " + spec + (vendorText == null ? "" : " " + vendorText) + " -- <command>");
        }
        boolean graalVm = chosen.vendor() != null && chosen.vendor().isGraalVmBased();
        Map<String, String> changes = ShellEnv.use(ctx.env, ctx.home, tool, chosen.home(), graalVm);
        ProjectShell.writeEnvFile(ctx, envFile, changes);
        Changes report = new Changes(ctx).env(changes)
                .note("only this shell; for new shells too: jkit use " + tool + "@" + chosen.version().raw()
                        + (chosen.vendor() == null ? "" : " " + chosen.vendor().name()) + (chosen.fx() ? " --fx" : "") + " --global");
        PinnedTool pin = projectPin(ctx, tool);
        if (pin != null) {
            report.note("overrides " + projectFile(ctx, tool) + "'s " + StatusCommand.pinText(pin)
                    + " in this shell; jkit reset " + tool + " to go back to it");
        }
        PrintStream say = json.json ? ctx.err : ctx.out;
        say.println("using " + tool + " " + chosen.label() + " in this shell");
        report.print(say);
        printJson(ctx, "shell", chosen, report, true);
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

    private static String projectFile(Context ctx, Tool tool) {
        try {
            return ProjectShell.pinFile(ProjectFile.find(Path.of("").toAbsolutePath(), ctx.env), tool);
        } catch (JkitException e) {
            return "the project file";
        }
    }

    /**
     * {@code {schemaVersion, scope: global|shell, tool, installed: {…}, changed, changes: […], notes: […]}};
     * {@code changed} is false when the global already was that install.
     */
    private void printJson(Context ctx, String scope, Installed chosen, Changes changes, boolean changed)
            throws Exception {
        if (!json.json) {
            return;
        }
        Map<String, Object> doc = JsonOut.document();
        doc.put("scope", scope);
        doc.put("tool", spec.tool().id());
        doc.put("installed", JsonOut.installed(ctx, chosen));
        doc.put("changed", changed);
        doc.put("changes", changes.json());
        doc.put("notes", changes.notes());
        ctx.out.println(JsonOut.write(doc));
    }
}
