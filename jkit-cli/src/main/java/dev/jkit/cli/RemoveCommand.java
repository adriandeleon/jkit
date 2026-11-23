package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "remove", description = {"Removes installed versions. Refuses anything global or chosen in this shell without --force.",
        "Warns, but doesn't refuse, when the project file here pins it.",
        "",
        "  jkit remove jdk@17.0.12 temurin",
        "  jkit remove jdk@17 --all         every installed 17 from the default vendor"})
final class RemoveCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>@<version>", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<vendor>", completionCandidates = Candidates.Vendors.class)
    String vendorText;

    @Option(names = "--all", description = "Remove every match.")
    boolean all;

    @Option(names = "--fx", description = "Remove JavaFX builds. Without it, plain builds are removed first.")
    boolean fx;

    @Option(names = "--force", description = "Remove even if global or active in this shell.")
    boolean force;

    @picocli.CommandLine.Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        spec = Resolve.lts(ctx, spec, false);
        Vendor vendor = Resolve.vendor(ctx, spec, vendorText);
        List<Installed> matches = Resolve.installed(ctx, spec, vendor, vendorText, fx);
        String vendorSuffix = (vendor == null ? "" : " " + vendor) + (fx ? " --fx" : "");
        if (matches.size() > 1 && !all) {
            List<String> hints = new ArrayList<>();
            for (Installed i : matches) {
                List<String> notes = ListCommand.notes(ctx, i);
                hints.add(i.version() + (notes.isEmpty() ? "" : "   (" + String.join(", ", notes) + ")"));
            }
            hints.add("remove one:  jkit remove " + spec.tool() + "@" + matches.getLast().version() + vendorSuffix);
            hints.add("remove all:  jkit remove " + spec + vendorSuffix + " --all");
            throw new JkitException(spec + vendorSuffix + " matches " + matches.size() + " installed builds",
                    hints.toArray(String[]::new));
        }
        // check every match first, so a refusal really means nothing was removed
        if (!force) {
            for (Installed i : matches) {
                List<String> notes = ListCommand.notes(ctx, i);
                if (!notes.isEmpty()) {
                    List<String> hints = new ArrayList<>();
                    if (notes.stream().anyMatch(n -> n.startsWith("global"))) {
                        hints.add("make another version the default first: jkit use " + i.tool() + "@<version> --global");
                        hints.add("or clear the default: jkit reset " + i.tool() + " --global");
                    }
                    hints.add("or remove it anyway (the link goes too): add --force");
                    throw new JkitException(i.tool() + " " + i.label() + " is " + String.join(", ", notes)
                            + "; nothing removed", hints.toArray(String[]::new));
                }
            }
        }
        List<Object> removed = new ArrayList<>();
        List<Object> changes = new ArrayList<>();
        ToolStates.Result here = StatusCommand.read(ctx, Path.of("").toAbsolutePath());
        for (Installed i : matches) {
            removed.add(JsonOut.installed(ctx, i));
            try (AutoCloseable lock = ctx.store.lock()) { // not while an install of it runs
                changes.addAll(remove(ctx, i, here, json.json ? ctx.err : ctx.out).json());
            } catch (Exception e) {
                if (changes.isEmpty()) {
                    throw e;
                }
                throw new PartialFailure(Errors.describe(e), changes); // the earlier ones are gone
            }
        }
        if (json.json) {
            java.util.Map<String, Object> doc = JsonOut.document();
            doc.put("removed", removed);
            doc.put("changes", changes);
            ctx.out.println(JsonOut.write(doc));
        }
        return 0;
    }

    /** Removes one install and its global links; prints and returns what changed. */
    private static Changes remove(Context ctx, Installed i, ToolStates.Result here, java.io.PrintStream say)
            throws IOException {
        Changes removedLinks = new Changes(ctx);
        Files.createDirectories(ctx.home.tmp());
        Path trashDir = Files.createTempDirectory(ctx.home.tmp(), "remove-");
        Path trash = trashDir.resolve(i.id());
        try {
            // one rename first: if anything holds the folder open (Windows), nothing is touched
            Files.move(i.home(), trash, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(trashDir);
            if (e instanceof java.nio.file.AccessDeniedException && !ctx.platform.isWindows()) {
                throw new JkitException("permission denied moving " + i.home(),
                        "check the folder's owner: ls -ld " + i.home().getParent());
            }
            throw new JkitException("can't remove " + i.tool() + " " + i.label() + ": " + e.getMessage(),
                    ctx.platform.isWindows()
                            ? "something may have it open (IDE or Gradle daemon?): try gradle --stop, then run this again"
                            : "check the folder's permissions, then run this again");
        }
        Fs.deleteRecursively(trash.getParent());
        removedLinks.folder(i.home(), false);
        for (Tool t : Tool.values()) {
            Path link = ctx.home.global(t);
            Path target = ctx.os.readLink(link);
            if (t.storeTool() == i.tool() && Resolve.same(target, i.home())) {
                ctx.os.removeLink(link);
                removedLinks.link(link, null);
                removedLinks.note("warning: there is no global " + t + " any more");
            }
            String shell = ctx.env.get(dev.jkit.core.ShellEnv.stateVariable(t));
            if (t.storeTool() == i.tool() && shell != null && Resolve.same(Path.of(shell), i.home())) {
                removedLinks.note("this shell's " + t.homeVariable() + " still points at the removed folder: jkit reset " + t);
            }
            String project = ctx.env.get(dev.jkit.core.ShellEnv.projectVariable(t));
            if (t.storeTool() == i.tool() && (shell == null || shell.isBlank()) && project != null
                    && Resolve.same(Path.of(project), i.home())) {
                removedLinks.note("this shell's " + t.homeVariable() + " still points at the removed folder "
                        + "(applied from a project file): jkit sync to install it again, or jkit reset " + t);
            }
        }
        for (ToolState s : here.tools()) {
            if (s.pinInstall() != null && Resolve.same(s.pinInstall().home(), i.home())) {
                removedLinks.note("warning: " + here.project().map(p -> p.source()).orElse("the project file")
                        + " here pins " + StatusCommand.pinText(s.pin()) + ", which this was: jkit sync installs it again");
            }
        }
        say.println("removed " + i.tool() + " " + i.label());
        removedLinks.print(say);
        return removedLinks;
    }
}
