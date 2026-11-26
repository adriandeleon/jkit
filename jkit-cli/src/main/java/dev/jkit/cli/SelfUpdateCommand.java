package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.SelfUpdate;
import dev.jkit.core.Signatures;
import dev.jkit.core.WorkDirs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Perms;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "self-update", description = {"Updates jkit itself to the newest release.",
        "A jkit from Homebrew, apt, dnf or winget is updated there: this says how.",
        "A jkit from a release archive is replaced in place, after checking the",
        "download against the release's SHA256SUMS, signed by jkit's release key,",
        "and that the new binary runs.",
        "",
        "  jkit self-update --check   only say whether a newer jkit is out"})
final class SelfUpdateCommand implements Callable<Integer> {

    @Option(names = "--check", description = "Only say whether a newer release is out; change nothing.")
    boolean check;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    /** How SHA256SUMS.asc is checked: jkit's release key; tests sign with their own. */
    SelfUpdate.SumsCheck sumsCheck = Signatures::verifyRelease;

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.online();
        return run(ctx, self(), VersionProvider.version());
    }

    /** This running jkit binary, links resolved; refuses when jkit runs on a JVM (a development build). */
    static Path self() throws IOException {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null || command == null) {
            throw new JkitException("self-update replaces the jkit binary, but this jkit runs on a JVM"
                    + (command == null ? "" : " (" + command + ")"), "build or download the native jkit");
        }
        return Path.of(command).toRealPath();
    }

    int run(Context ctx, Path binary, String current) throws Exception {
        PrintStream say = json.json ? ctx.err : ctx.out;
        Changes report = new Changes(ctx);
        SelfUpdate.Kind kind = SelfUpdate.detect(binary, ctx.platform, Files::exists);
        if (ctx.platform.isWindows() && kind == SelfUpdate.Kind.ARCHIVE && !check) {
            for (Path old : SelfUpdate.deleteOld(binary)) {
                report.fileDeleted(old); // left by the last update
            }
        }
        URI base = ctx.url("JKIT_SOURCES_RELEASES_URL", SelfUpdate.DEFAULT_BASE);
        String sums = ctx.http().getFresh(SelfUpdate.sumsUrl(base));
        SelfUpdate.Release latest = SelfUpdate.parse(sums, ctx.platform);
        // the signature guards the binary jkit is about to run, so it's checked just before the
        // download: saying a release is out, or which command updates it, needs no trust in it
        boolean newer = SelfUpdate.newer(latest, current);
        String how = kind.command(latest, ctx.platform);
        String status;
        if (!newer) {
            status = "up-to-date";
            // a development build or a pre-release can be ahead of the newest release
            say.println(latest.version().raw().equals(current) ? "jkit " + current + " is the newest release"
                    : "jkit " + current + " is up to date: the newest release is " + latest.version());
        } else if (check) {
            status = "available";
            say.println("jkit " + latest.version() + " is out (this is " + current + ")");
            report.note("to update: " + (how == null ? "jkit self-update" : how)
                    + (how == null ? "" : "  (jkit came from " + kind.what + ")"));
        } else if (kind != SelfUpdate.Kind.ARCHIVE) {
            status = "managed";
            say.println("jkit " + latest.version() + " is out (this is " + current + "); jkit came from " + kind.what
                    + ", so update it there:");
            say.println("  " + how);
        } else {
            status = "updated";
            SelfUpdate.requireSigned(ctx.http(), base, latest, sums, sumsCheck);
            List<Path> olds = update(ctx, base, latest, binary, report);
            say.println("updated jkit " + current + " → " + latest.version() + " at " + Changes.tilde(binary.toString()));
            if (olds.size() == 1) {
                report.note("the old binary is " + Changes.tilde(olds.get(0).toString())
                        + " until the next self-update deletes it");
            } else if (!olds.isEmpty()) {
                report.note("the old files (" + olds.stream().map(o -> o.getFileName().toString())
                        .collect(Collectors.joining(", ")) + ") stay in "
                        + Changes.tilde(binary.getParent().toString()) + " until the next self-update deletes them");
            }
        }
        report.print(say);
        if (json.json) {
            Map<String, Object> doc = JsonOut.document();
            doc.put("current", current);
            doc.put("latest", latest.version().raw());
            doc.put("status", status);
            doc.put("installedWith", kind.id());
            doc.put("binary", binary);
            doc.put("command", newer && kind != SelfUpdate.Kind.ARCHIVE ? how : null);
            doc.put("changes", report.json());
            doc.put("notes", report.notes());
            ctx.out.println(JsonOut.write(doc));
        }
        return 0;
    }

    /**
     * Downloads, checks and puts the new binary in place, after the runtime DLLs beside it on Windows
     * (a newer runtime runs the old jkit.exe, an older one may not run the new); the .old files
     * Windows leaves.
     */
    private static List<Path> update(Context ctx, URI base, SelfUpdate.Release latest, Path binary, Changes report)
            throws Exception {
        Path work;
        // Created under the install lock, as an install creates its own: an install clears
        // tmp/install-* folders no running jkit holds, and this one is held from the moment it
        // exists. The download and the test run of the new binary then go without the lock, so
        // installs elsewhere don't wait for them.
        try (AutoCloseable lock = ctx.store.lock()) {
            Perms.mkdirs(ctx.home.tmp());
            work = WorkDirs.create(ctx.home.tmp(), "install-self-");
        }
        try {
            Path fresh = SelfUpdate.fetch(ctx.http(), base, latest, ctx.platform, work, new ProgressBar(ctx.err));
            // Under the lock again, briefly: two self-updates at once must not interleave their
            // renames of the binary and the DLLs beside it.
            try (AutoCloseable lock = ctx.store.lock()) {
                List<Path> olds = new ArrayList<>();
                if (ctx.platform.isWindows()) {
                    for (String name : SelfUpdate.runtimeToReplace(fresh, binary)) {
                        Path dll = binary.resolveSibling(name);
                        Path old = SelfUpdate.replace(fresh.resolveSibling(name), dll, true);
                        report.file(dll, false);
                        report.file(old, true);
                        olds.add(old);
                    }
                }
                Path old;
                try {
                    old = SelfUpdate.replace(fresh, binary, ctx.platform.isWindows());
                } catch (IOException | JkitException e) {
                    if (olds.isEmpty()) {
                        throw e;
                    }
                    // say what did change: the DLLs are newer, which the old jkit.exe runs with
                    throw new JkitException("couldn't replace " + Changes.tilde(binary.toString()) + " (" + e.getMessage()
                            + "); the Visual C++ runtime DLLs beside it were already updated, which the old jkit.exe also runs with",
                            "run jkit self-update again");
                }
                report.file(binary, false);
                if (old != null) {
                    report.file(old, true);
                    olds.add(0, old);
                }
                return olds;
            }
        } finally {
            WorkDirs.remove(work); // releases its lock first, since Windows can't delete an open file
            try {
                Fs.deleteRecursively(work);
            } catch (IOException e) {
                // a leftover in tmp/ is cleaned up by the next install or jkit prune
            }
        }
    }
}
