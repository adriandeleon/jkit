package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ShellEnv;
import dev.jkit.model.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "list", description = "Shows what is installed. Never uses the network.")
final class ListCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        boolean any = false;
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE)) {
            List<Installed> installed = ctx.store.list(tool);
            String label = tool.id();
            for (Installed i : installed) {
                List<String> notes = notes(ctx, i);
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

    static List<String> notes(Context ctx, Installed i) throws Exception {
        List<String> notes = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (t.storeTool() != i.tool()) {
                continue;
            }
            Path global = ctx.os.readLink(ctx.home.global(t));
            if (global != null && global.equals(i.home())) {
                notes.add(t == Tool.GRAALVM ? "global graalvm" : "global");
            }
            String shell = ctx.env.get(ShellEnv.stateVariable(t));
            if (shell != null && Path.of(shell).equals(i.home())) {
                notes.add(t == Tool.GRAALVM ? "graalvm in this shell" : "this shell");
            }
        }
        return notes;
    }
}
