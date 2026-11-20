package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
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
            (json.json ? ctx.err : ctx.out).println("global " + tool + " → " + chosen.label());
            printJson(ctx, "global", chosen);
            return 0;
        }
        String envFile = ctx.env.get("JKIT_ENV_FILE");
        if (envFile == null) {
            throw new JkitException("the jkit shell hook is not active, so this shell can't be changed",
                    "add to ~/.bashrc:  eval \"$(jkit hook bash)\"",
                    "or set the default: jkit use " + spec + (vendorText == null ? "" : " " + vendorText) + " --global");
        }
        boolean graalVm = chosen.vendor() != null && chosen.vendor().isGraalVmBased();
        Map<String, String> changes = ShellEnv.use(ctx.env, ctx.home, tool, chosen.home(), graalVm);
        Files.writeString(Path.of(envFile), ShellEnv.format(changes, ctx.env.getOrDefault("JKIT_SHELL", "bash")));
        (json.json ? ctx.err : ctx.out).println("using " + tool + " " + chosen.label() + " in this shell");
        printJson(ctx, "shell", chosen);
        return 0;
    }

    /** {@code {schemaVersion, scope: global|shell, tool, installed: {…}}}. */
    private void printJson(Context ctx, String scope, Installed chosen) throws Exception {
        if (!json.json) {
            return;
        }
        java.util.Map<String, Object> doc = JsonOut.document();
        doc.put("scope", scope);
        doc.put("tool", spec.tool().id());
        doc.put("installed", JsonOut.installed(ctx, chosen));
        ctx.out.println(JsonOut.write(doc));
    }
}
