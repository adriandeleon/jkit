package dev.jkit.cli;

import dev.jkit.core.Installer;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.sources.Artifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "install", description = {"Resolves and installs a version. Never prompts.",
        "The first install of a tool also becomes its default (see --no-default).",
        "",
        "  jkit install jdk@21              newest 21 from the default vendor (temurin)",
        "  jkit install jdk@lts zulu        newest long-term-support release from Zulu",
        "  jkit install maven               newest Maven",
        "  jkit install gradle@8.14"})
final class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class,
            completionCandidates = Candidates.Tools.class,
            description = "jdk, graalvm, maven, gradle or sbt; @21, @21.0.5, @lts or @latest (the default).")
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<vendor>", completionCandidates = Candidates.Vendors.class,
            description = "JDK vendor, e.g. temurin, zulu, corretto (see: jkit search jdk).")
    String vendorText;

    @Option(names = "--fx", description = "JavaFX build.")
    boolean fx;

    @Option(names = "--pre", description = "Allow pre-releases.")
    boolean pre;

    @Option(names = "--no-default",
            description = "Don't make it the default, even when it's the first of its tool.")
    boolean noDefault;

    @Option(names = "--allow-unverified",
            description = "Install even when the source publishes no checksum for the download.")
    boolean allowUnverified;

    @Mixin
    Options options;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /**
     * Downloads and installs {@code artifact}, saying beforehand what can't be checked: no checksum
     * at all is refused unless {@code allowUnverified} ({@code retry} is the command that allows it),
     * md5 or sha1 only is a warning. A project's jkit sync must not install unchecked code unasked.
     */
    static Installer.Result download(Context ctx, Artifact artifact, boolean allowUnverified, String retry)
            throws Exception {
        if (ctx.http().offline()) {
            throw new dev.jkit.model.JkitException("--offline, so " + artifact.label() + " can't be downloaded",
                    "run the command without --offline");
        }
        if (artifact.checksum() == null) {
            if (!allowUnverified) {
                throw new dev.jkit.model.JkitException(artifact.label() + " from " + artifact.url().getHost()
                        + " has no published checksum, so jkit can't check the download; nothing installed",
                        "install it anyway: " + retry, "or pick another version or vendor");
            }
            ctx.err.println("warning: " + artifact.label() + " has no published checksum; installing it unchecked"
                    + " (--allow-unverified)");
        } else if (!isStrong(artifact.checksumAlgorithm())) {
            ctx.err.println("warning: only a " + artifact.checksumAlgorithm() + " checksum is published for "
                    + artifact.label() + ": it catches a broken download, not a tampered one");
        }
        ctx.err.println("downloading " + artifact.label() + " from " + artifact.url().getHost());
        return ctx.installer().install(artifact, new ProgressBar(ctx.err));
    }

    private static boolean isStrong(String algorithm) {
        String a = algorithm == null ? "" : algorithm.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return a.equals("sha256") || a.equals("sha384") || a.equals("sha512");
    }

    @Override
    public Integer call() throws Exception {
        Context ctx = options.context();
        spec = Resolve.lts(ctx, spec, true);
        Vendor vendor = Resolve.knownVendor(ctx, spec, vendorText);
        // an exact pre-release (maven@4.0.0-rc-7) asks for pre-releases by itself, as pin and sync do
        boolean wantPre = pre || spec.version() != null && dev.jkit.model.Version.looksLikeVersion(spec.version())
                && dev.jkit.model.Version.parse(spec.version()).isPreRelease();
        Artifact artifact = artifact(ctx, spec, vendor, fx, wantPre);
        java.io.PrintStream say = json.json ? ctx.err : ctx.out;
        if (ctx.store.contains(spec.tool(), artifact.version(), artifact.vendor(), artifact.fx())) {
            say.println(artifact.label() + " is already installed");
            Changes changes = new Changes(ctx).disk();
            changes.print(say);
            if (json.json) {
                printJson(ctx, "already-installed", null, artifact.version(), artifact.vendor(), artifact.fx(), changes);
            }
            return 0;
        }
        // only the very first build of a tool: after jkit reset --global, no default was a choice
        // GraalVM builds count only for graalvm; for jdk, a GraalVM installed with jkit install graalvm
        // doesn't make a plain JDK "not the first"
        boolean installingGraal = artifact.vendor() != null && artifact.vendor().isGraalVmBased();
        boolean first = ctx.store.list(spec.tool()).stream().noneMatch(i -> {
            boolean graal = i.vendor() != null && i.vendor().isGraalVmBased();
            return spec.tool() == Tool.GRAALVM ? graal : installingGraal || !graal;
        });
        Installer.Result result = download(ctx, artifact, allowUnverified, "jkit install " + spec
                + (vendorText == null ? "" : " " + vendorText) + (fx ? " --fx" : "") + (pre ? " --pre" : "")
                + " --allow-unverified");
        Changes changes = installed(ctx, result);
        // the first one of a tool becomes the default, as most people would do next anyway
        Path globalLink = ctx.home.global(spec.tool());
        boolean madeDefault = result.fresh() && first && !noDefault && ctx.os.readLink(globalLink) == null && !Files.exists(globalLink);
        if (madeDefault) {
            ctx.os.pointLink(globalLink, result.installed().home());
            changes.link(globalLink, result.installed().home()).globalEffect(spec.tool());
            changes.note("it's the default " + spec.tool() + " now (the first one installed); to clear it: jkit reset "
                    + spec.tool() + " --global");
        }
        // another jkit may have installed it while this one waited for the store lock
        say.println((result.fresh() ? "installed " : "already installed (by another jkit just now): ")
                + artifact.label() + " → " + Changes.tilde(result.installed().home().toString()));
        changes.print(say);
        if (json.json) {
            printJson(ctx, result.fresh() ? "installed" : "already-installed", result.verified(), artifact.version(), artifact.vendor(), artifact.fx(), changes);
            return 0;
        }
        if (!madeDefault) {
            ctx.out.println("make it the default: jkit use " + spec + (vendorText == null ? "" : " " + vendorText)
                    + (fx ? " --fx" : "") + " --global");
        }
        return 0;
    }

    /** The download for {@code spec}: from foojay for JDKs and GraalVM, else the tool's own source. */
    static Artifact artifact(Context ctx, ToolSpec spec, Vendor vendor, boolean fx, boolean pre) throws Exception {
        return switch (spec.tool()) {
            case JDK, GRAALVM -> ctx.foojay().resolve(spec.tool(), spec.version(), vendor, ctx.platform, fx, pre);
            case MAVEN -> ctx.maven().resolve(spec.version(), pre);
            case GRADLE -> ctx.gradle().resolve(spec.version(), pre);
            case SBT -> ctx.sbt().resolve(spec.version(), pre);
        };
    }

    /** What an install changed on disk: the home if new, leftovers it cleared, the install folder, cached lists. */
    static Changes installed(Context ctx, Installer.Result result) {
        Changes changes = new Changes(ctx).disk();
        for (java.nio.file.Path leftover : result.removedLeftovers()) {
            changes.folder(leftover, false);
        }
        if (result.fresh()) {
            changes.folder(result.installed().home(), true);
        }
        return changes;
    }

    /** {@code {schemaVersion, status: installed|already-installed, verified, installed: {…}, changes: […]}}. */
    private void printJson(Context ctx, String status, Boolean verified, dev.jkit.model.Version version,
                           Vendor vendor, boolean fxBuild, Changes changes) throws Exception {
        java.util.Map<String, Object> doc = JsonOut.document();
        doc.put("status", status);
        doc.put("verified", verified);
        for (dev.jkit.core.Installed i : ctx.store.list(spec.tool().storeTool())) {
            if (i.version().equals(version) && java.util.Objects.equals(i.vendor(), vendor) && i.fx() == fxBuild) {
                doc.put("installed", JsonOut.installed(ctx, i));
            }
        }
        doc.put("changes", changes.json());
        ctx.out.println(JsonOut.write(doc));
    }
}
