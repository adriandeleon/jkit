package dev.jkit.cli;

import dev.jkit.core.ProjectFile;
import dev.jkit.core.ProjectWriter;
import dev.jkit.model.JkitException;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.Tool;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "unpin", description = "Removes a tool from the project's jkit.toml; it follows the shell or global choice again.")
final class UnpinCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<tool>", completionCandidates = Candidates.Tools.class,
            converter = ResetCommand.ToolConverter.class)
    Tool tool;

    @Option(names = "--here", description = "Use jkit.toml in this folder, even if a project file is further up.")
    boolean here;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        return run(Context.create(false, true), Path.of("").toAbsolutePath());
    }

    int run(Context ctx, Path cwd) throws Exception {
        Path file = ProjectShell.target(ctx, cwd, here, "jkit unpin " + tool);
        if (!Files.isRegularFile(file)) {
            throw new JkitException("no jkit.toml here or above, so there is nothing to unpin");
        }
        PinnedTool previous = ProjectFile.read(file).get(tool);
        ProjectWriter.Edit edit = ProjectWriter.remove(file, ProjectFile.text(file), tool);
        PrintStream say = json.json ? ctx.err : ctx.out;
        Changes report = new Changes(ctx);
        String name = Changes.tilde(file.toString());
        if (edit.replaced()) {
            ProjectShell.write(file, edit.text());
            report.file(file, false);
            say.println("unpinned " + StatusCommand.pinText(previous) + " from " + name);
        } else {
            say.println(name + " doesn't pin " + tool);
        }
        ProjectShell.apply(ctx, cwd, report);
        report.print(say);
        if (json.json) {
            Map<String, Object> doc = JsonOut.document();
            doc.put("file", file);
            doc.put("tool", tool.id());
            doc.put("removed", edit.replaced());
            doc.put("previous", previous == null ? null : PinCommand.pinJson(previous));
            doc.put("changes", report.json());
            doc.put("notes", report.notes());
            ctx.out.println(JsonOut.write(doc));
        }
        return 0;
    }
}
