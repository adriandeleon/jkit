package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.Installer;
import dev.jkit.core.Signatures;
import dev.jkit.core.Store;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.Artifact;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
            description = "Install even when the download has no published checksum, or its publisher's signature can't be checked.")
    boolean allowUnverified;

    @Mixin
    Options options;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /**
     * Downloads and installs {@code artifact}, first telling the user what can't be checked.
     *
     * <p>A download with no checksum at all is refused unless {@code allowUnverified}, so that a
     * project's jkit sync never installs unchecked code without being asked to; {@code retry} is
     * the command line that allows it. An md5 or sha1 checksum only gets a warning. If the
     * publisher signs its downloads with a key built into jkit, the signature must check out too,
     * unless {@code allowUnverified}. For downloads without a checksum (Corretto) the signature is
     * the only check.
     */
    static Installer.Result download(Context ctx, Artifact artifact, boolean allowUnverified, String retry)
            throws Exception {
        if (ctx.http().offline()) {
            throw new JkitException("--offline, so " + artifact.label() + " can't be downloaded",
                    "run the command without --offline");
        }
        String signer = signer(artifact);
        // When the publisher signs its downloads, a signature jkit can't fetch or check refuses the
        // install: whoever can replace a download can usually replace the checksum next to it too.
        boolean signatureRequired = signer != null && !allowUnverified;
        if (artifact.checksum() == null && signer != null) {
            ctx.err.println("note: " + artifact.label() + " has no published checksum; checking " + signer
                    + "'s signature instead");
        } else if (artifact.checksum() == null) {
            if (!allowUnverified) {
                throw new JkitException(artifact.label() + " from " + artifact.url().getHost()
                        + " has no published checksum, so jkit can't check the download; nothing installed",
                        "install it anyway: " + retry, "or pick another version or vendor");
            }
            ctx.err.println("warning: " + artifact.label() + " has no published checksum; installing it unchecked"
                    + " (--allow-unverified)");
        } else if (!isStrong(artifact.checksumAlgorithm())) {
            ctx.err.println("warning: only a " + artifact.checksumAlgorithm() + " checksum is published for "
                    + artifact.label() + ": it catches a broken download, not a tampered one");
        }
        // The progress bar prints this when the download actually starts. The signature is fetched
        // and checked first, and if it refuses the install nothing is downloaded at all.
        ProgressBar progress = new ProgressBar(ctx.err,
                "downloading " + artifact.label() + " from " + artifact.url().getHost());
        try {
            return ctx.installer().install(artifact, progress, signatureRequired);
        } catch (Installer.SignatureRequired e) {
            throw new JkitException(e.getMessage(), "install it anyway: " + retry,
                    "or pick another version or vendor");
        }
    }

    /** Whose signature checks {@code artifact} (and can stand in for a missing checksum), or null. */
    static String signer(Artifact artifact) {
        return artifact.signature() == null ? null
                : Signatures.owner(artifact.tool(), artifact.vendor());
    }

    private static boolean isStrong(String algorithm) {
        String a = algorithm == null ? "" : algorithm.toLowerCase(Locale.ROOT).replace("-", "");
        return a.equals("sha256") || a.equals("sha384") || a.equals("sha512");
    }

    @Override
    public Integer call() throws Exception {
        Context ctx = options.context();
        Vendor vendor = Resolve.knownVendor(ctx, spec, vendorText);
        spec = Resolve.lts(ctx, spec, true, vendor, fx);
        // Asking for an exact pre-release (maven@4.0.0-rc-7) implies --pre, as in pin and sync.
        boolean wantPre = pre || spec.version() != null && Version.looksLikeVersion(spec.version())
                && Version.parse(spec.version()).isPreRelease();
        Artifact artifact = artifact(ctx, spec, vendor, fx, wantPre);
        PrintStream say = json.json ? ctx.err : ctx.out;
        if (ctx.store.contains(spec.tool(), artifact.version(), artifact.vendor(), artifact.fx())) {
            say.println(artifact.label() + " is already installed");
            Changes changes = new Changes(ctx).disk().namedInMessage(null);
            changes.print(say);
            if (json.json) {
                printJson(ctx, "already-installed", null, null, artifact.version(), artifact.vendor(), artifact.fx(), changes);
            }
            return 0;
        }
        // Only the very first build of a tool becomes the default: if the user cleared the default
        // with jkit reset --global, later installs must not set it again. GraalVM builds count
        // separately, so a GraalVM installed with jkit install graalvm doesn't stop the first plain
        // JDK from becoming the default.
        boolean installingGraal = artifact.vendor() != null && artifact.vendor().isGraalVmBased();
        boolean first = ctx.store.list(spec.tool()).stream().noneMatch(i -> {
            boolean graal = i.vendor() != null && i.vendor().isGraalVmBased();
            return spec.tool() == Tool.GRAALVM ? graal : installingGraal || !graal;
        });
        Installer.Result result = download(ctx, artifact, allowUnverified, "jkit install " + spec
                + (vendorText == null ? "" : " " + vendorText) + (fx ? " --fx" : "") + (pre ? " --pre" : "")
                + " --allow-unverified");
        Changes changes = installed(ctx, result);
        Path globalLink = ctx.home.global(spec.tool());
        boolean madeDefault = false;
        if (result.fresh() && first && !noDefault) {
            // Checked and set under the link lock, since another jkit may set a default or remove this
            // install in the meantime.
            try (AutoCloseable lock = ctx.store.linkLock()) {
                if (ctx.os.readLink(globalLink) == null && !Files.exists(globalLink, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(result.installed().home().resolve(Store.MARKER),
                                LinkOption.NOFOLLOW_LINKS)) {
                    ctx.os.pointLink(globalLink, result.installed().home());
                    madeDefault = true;
                }
            }
        }
        if (madeDefault) {
            changes.link(globalLink, result.installed().home()).globalEffect(spec.tool());
            changes.note("it's the default " + spec.tool() + " now (the first one installed); to clear it: jkit reset "
                    + spec.tool() + " --global");
        }
        // Not fresh means another jkit installed it while this one waited for the store lock.
        say.println((result.fresh() ? "installed " : "already installed (by another jkit just now): ")
                + artifact.label() + " → " + Changes.tilde(result.installed().home().toString()));
        changes.namedInMessage(result.installed().home()).print(say);
        if (json.json) {
            printJson(ctx, result.fresh() ? "installed" : "already-installed", result.fresh() ? result.verified() : null,
                    result.fresh() ? result.signedBy() : null, artifact.version(), artifact.vendor(), artifact.fx(), changes);
            return 0;
        }
        if (!madeDefault && !isDefault(ctx, globalLink, result.installed().home())) {
            ctx.out.println("  make it the default: jkit use " + spec + (vendorText == null ? "" : " " + vendorText)
                    + (fx ? " --fx" : "") + " --global");
        }
        return 0;
    }

    /**
     * Whether the global link points at {@code home}. Checked again after the install because the
     * jkit that won an install race may have made it the default, and then the hint on how to
     * make it the default would be wrong.
     */
    static boolean isDefault(Context ctx, Path link, Path home) {
        try {
            Path target = ctx.os.readLink(link);
            return target != null && Fs.same(target, home);
        } catch (IOException e) {
            return false;
        }
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
        for (Path leftover : result.removedLeftovers()) {
            changes.folder(leftover, false);
        }
        if (result.fresh()) {
            changes.folder(result.installed().home(), true);
        }
        result.notes().forEach(changes::note); // what the signature check found, if anything
        return changes;
    }

    /** {@code {schemaVersion, status: installed|already-installed, verified, signedBy, installed: {…}, changes: […]}}. */
    private void printJson(Context ctx, String status, Boolean verified, String signedBy, Version version,
                           Vendor vendor, boolean fxBuild, Changes changes) throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("status", status);
        doc.put("verified", verified);
        doc.put("signedBy", signedBy);
        for (Installed i : ctx.store.list(spec.tool().storeTool())) {
            if (i.version().equals(version) && Objects.equals(i.vendor(), vendor) && i.fx() == fxBuild) {
                doc.put("installed", JsonOut.installed(ctx, i));
            }
        }
        doc.put("changes", changes.json());
        doc.put("notes", changes.notes());
        ctx.out.println(JsonOut.write(doc));
    }
}
