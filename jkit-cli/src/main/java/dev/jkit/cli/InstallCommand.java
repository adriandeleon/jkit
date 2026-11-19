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

    @Parameters(index = "0", paramLabel = "<tool>@<version>", converter = ToolSpecConverter.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]")
    String vendorText;

    @Option(names = "--fx", description = "JavaFX build.")
    boolean fx;

    @Option(names = "--pre", description = "Allow pre-releases.")
    boolean pre;

    @Mixin
    Options options;

    @Override
    public Integer call() throws Exception {
        Context ctx = options.context();
        Vendor vendor = Resolve.vendor(ctx, spec, vendorText);
        Artifact artifact = switch (spec.tool()) {
            case JDK, GRAALVM -> ctx.foojay().resolve(spec.tool(), spec.version(), vendor, ctx.platform, fx, pre);
            case MAVEN -> ctx.maven().resolve(spec.version(), pre);
            case GRADLE -> ctx.gradle().resolve(spec.version(), pre);
        };
        if (ctx.store.contains(spec.tool(), artifact.version(), artifact.vendor())) {
            ctx.out.println(artifact.label() + " is already installed");
            return 0;
        }
        ctx.err.println("downloading " + artifact.label() + " from " + artifact.url().getHost());
        Installer.Result result = ctx.installer().install(artifact, new ProgressBar(ctx.err));
        if (!result.verified()) {
            ctx.err.println("warning: the source publishes no checksum; the download was not verified");
        }
        ctx.out.println("installed " + artifact.label() + " → " + result.installed().home());
        if (!Files.exists(ctx.home.global(spec.tool()))) {
            ctx.out.println("make it the default: jkit use " + spec.tool() + "@" + artifact.version()
                    + (vendor == null ? "" : " " + vendor) + " --global");
        }
        return 0;
    }
}
