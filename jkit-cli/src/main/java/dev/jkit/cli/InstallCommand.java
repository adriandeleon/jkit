package dev.jkit.cli;

import dev.jkit.core.Installer;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.sources.Artifact;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "install", description = "Resolves and installs a version. Never prompts.")
final class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>@<version>", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--fx", description = "JavaFX build.")
    boolean fx;

    @Option(names = "--pre", description = "Allow pre-releases.")
    boolean pre;

    @Mixin
    Options options;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        Context ctx = options.context();
        Vendor vendor = Resolve.knownVendor(ctx, spec, vendorText);
        Artifact artifact = switch (spec.tool()) {
            case JDK, GRAALVM -> ctx.foojay().resolve(spec.tool(), spec.version(), vendor, ctx.platform, fx, pre);
            case MAVEN -> ctx.maven().resolve(spec.version(), pre);
            case GRADLE -> ctx.gradle().resolve(spec.version(), pre);
        };
        java.io.PrintStream say = json.json ? ctx.err : ctx.out;
        if (ctx.store.contains(spec.tool(), artifact.version(), artifact.vendor(), artifact.fx())) {
            say.println(artifact.label() + " is already installed");
            if (json.json) {
                printJson(ctx, "already-installed", null, artifact.version(), artifact.vendor(), artifact.fx());
            }
            return 0;
        }
        ctx.err.println("downloading " + artifact.label() + " from " + artifact.url().getHost());
        Installer.Result result = ctx.installer().install(artifact, new ProgressBar(ctx.err));
        if (!result.verified()) {
            ctx.err.println("warning: the source publishes no checksum; the download was not verified");
        }
        say.println("installed " + artifact.label() + " → " + result.installed().home());
        if (json.json) {
            printJson(ctx, "installed", result.verified(), artifact.version(), artifact.vendor(), artifact.fx());
            return 0;
        }
        if (!Files.exists(ctx.home.global(spec.tool()))) {
            ctx.out.println("make it the default: jkit use " + spec.tool() + "@" + artifact.version()
                    + (vendor == null ? "" : " " + vendor) + " --global");
        }
        return 0;
    }

    /** {@code {schemaVersion, status: installed|already-installed, verified, installed: {…}}}. */
    private void printJson(Context ctx, String status, Boolean verified, dev.jkit.model.Version version,
                           Vendor vendor, boolean fxBuild) throws Exception {
        java.util.Map<String, Object> doc = JsonOut.document();
        doc.put("status", status);
        doc.put("verified", verified);
        for (dev.jkit.core.Installed i : ctx.store.list(spec.tool().storeTool())) {
            if (i.version().equals(version) && java.util.Objects.equals(i.vendor(), vendor) && i.fx() == fxBuild) {
                doc.put("installed", JsonOut.installed(ctx, i));
            }
        }
        ctx.out.println(JsonOut.write(doc));
    }
}
