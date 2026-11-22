package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
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

@Command(name = "remove", description = "Removes installed versions. Refuses anything in use without --force.")
final class RemoveCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>@<version>", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class)
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]", completionCandidates = Candidates.Vendors.class)
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
                    throw new JkitException(i.tool() + " " + i.label() + " is " + String.join(", ", notes)
                            + "; nothing removed", "remove it anyway: add --force");
                }
            }
        }
        List<Object> removed = new ArrayList<>();
        for (Installed i : matches) {
            removed.add(JsonOut.installed(ctx, i));
            try (AutoCloseable lock = ctx.store.lock()) { // not while an install of it runs
                remove(ctx, i, json.json ? ctx.err : ctx.out);
            }
        }
        if (json.json) {
            java.util.Map<String, Object> doc = JsonOut.document();
            doc.put("removed", removed);
            ctx.out.println(JsonOut.write(doc));
        }
        return 0;
    }

    private static void remove(Context ctx, Installed i, java.io.PrintStream say) throws IOException {
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
        for (Tool t : Tool.values()) {
            Path link = ctx.home.global(t);
            Path target = ctx.os.readLink(link);
            if (t.storeTool() == i.tool() && target != null && target.equals(i.home())) {
                ctx.os.removeLink(link);
                removedLinks.link(link, null);
                ctx.err.println("warning: there is no global " + t + " any more");
            }
            String shell = ctx.env.get(dev.jkit.core.ShellEnv.stateVariable(t));
            if (t.storeTool() == i.tool() && shell != null && Path.of(shell).equals(i.home())) {
                removedLinks.note("this shell's " + t.homeVariable() + " still points at the removed folder: jkit reset " + t);
            }
        }
        say.println("removed " + i.tool() + " " + i.label());
        removedLinks.print(say);
    }
}
