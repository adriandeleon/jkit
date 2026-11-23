package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolStates;
import dev.jkit.model.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list", description = "Shows what is installed. Never uses the network.")
final class ListCommand implements Callable<Integer> {

    @picocli.CommandLine.Mixin
    JsonOut.Flag jsonFlag = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.readOnly();
        if (jsonFlag.json) {
            ctx.out.println(JsonOut.write(json(ctx)));
            return 0;
        }
        ToolStates.Result here = StatusCommand.read(ctx, Path.of("").toAbsolutePath());
        boolean any = false;
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            List<Installed> installed = ctx.store.list(tool);
            String label = tool.id();
            for (Installed i : installed) {
                List<String> notes = new ArrayList<>(notes(ctx, i));
                notes.addAll(projectNotes(here, i));
                ctx.out.println(String.format("%-8s%-22s%-14s%s", label, i.version(),
                        i.vendor() == null ? "" : i.vendor().name() + (i.fx() ? " fx" : ""), String.join(" · ", notes)).stripTrailing());
                label = "";
                any = true;
            }
        }
        if (!any) {
            ctx.out.println("nothing installed yet · try: jkit search jdk");
        }
        return 0;
    }

    /**
     * {@code home}, {@code global} (tool → install folder or null, for jdk, maven, gradle, sbt and graalvm),
     * {@code project} ({@code {dir, files, tools: {tool → install folder or null}}} for the project
     * file found from the working directory, or null) and {@code installed}: one object per install
     * ({@link JsonOut#installed}), newest first within a tool.
     */
    static Map<String, Object> json(Context ctx) throws Exception {
        return json(ctx, Path.of("").toAbsolutePath());
    }

    static Map<String, Object> json(Context ctx, Path cwd) throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("home", ctx.home.root());
        Map<String, Object> global = JsonOut.fields();
        for (Tool t : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT, Tool.GRAALVM)) {
            global.put(t.id(), ctx.os.readLink(ctx.home.global(t)));
        }
        doc.put("global", global);
        ToolStates.Result here = StatusCommand.read(ctx, cwd);
        Object project = null;
        if (here.project().isPresent()) {
            Map<String, Object> tools = JsonOut.fields();
            for (ToolState s : here.tools()) {
                if (s.pin() != null) {
                    tools.put(s.tool().id(), s.pinInstall() == null ? null : s.pinInstall().home());
                }
            }
            project = JsonOut.fields("dir", here.project().get().dir(), "files", here.project().get().files(),
                    "tools", tools);
        }
        doc.put("project", project);
        List<Object> installed = new ArrayList<>();
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            for (Installed i : ctx.store.list(tool)) {
                installed.add(JsonOut.installed(ctx, i));
            }
        }
        doc.put("installed", installed);
        return doc;
    }

    /** {@code jkit.toml} (or {@code graalvm in jkit.toml}) when the project found here picks {@code i}. */
    static List<String> projectNotes(ToolStates.Result here, Installed i) {
        List<String> notes = new ArrayList<>();
        if (here.project().isEmpty()) {
            return notes;
        }
        String file = here.project().get().source();
        for (ToolState s : here.tools()) {
            if (s.pinInstall() != null && Resolve.same(s.pinInstall().home(), i.home())) {
                notes.add(s.tool() == Tool.GRAALVM ? "graalvm in " + file : file);
            }
        }
        return notes;
    }

    static List<String> notes(Context ctx, Installed i) throws Exception {
        List<String> notes = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (t.storeTool() != i.tool()) {
                continue;
            }
            Path global = ctx.os.readLink(ctx.home.global(t));
            if (Resolve.same(global, i.home())) {
                notes.add(t == Tool.GRAALVM ? "global graalvm" : "global");
            }
            String shell = ctx.env.get(ShellEnv.stateVariable(t));
            if (shell != null && Resolve.same(Path.of(shell), i.home())) {
                notes.add(t == Tool.GRAALVM ? "graalvm in this shell" : "this shell");
            }
        }
        return notes;
    }
}
