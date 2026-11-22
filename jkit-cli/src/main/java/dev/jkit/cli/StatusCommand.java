package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolState.Source;
import dev.jkit.core.ToolStates;
import dev.jkit.model.PinnedTool;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "status", description = "Shows which version of each tool is active here, and why. Never uses the network.")
final class StatusCommand implements Callable<Integer> {

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.create(false, true);
        Path cwd = Path.of("").toAbsolutePath();
        ToolStates.Result result = read(ctx, cwd);
        if (json.json) {
            ctx.out.println(JsonOut.write(json(ctx, result)));
        } else {
            print(ctx, result, ctx.out);
        }
        return 0;
    }

    static ToolStates.Result read(Context ctx, Path cwd) throws Exception {
        return ToolStates.read(ctx.env, cwd, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
    }

    // text

    static void print(Context ctx, ToolStates.Result result, PrintStream out) throws Exception {
        boolean any = false;
        for (ToolState s : result.tools()) {
            if (s.isEmpty()) {
                if (s.tool() != Tool.GRAALVM && !ctx.store.list(s.tool()).isEmpty()) {
                    // installed but nothing chooses it
                    out.println(row(s.tool().id(), "-", "not set", ""));
                    out.println(indent(installedHint(ctx, s.tool())));
                    any = true;
                }
                continue;
            }
            out.println(row(s.tool().id(), version(s), label(s.source()), detail(s, result)));
            String hint = hint(ctx, s, result);
            if (hint != null) {
                out.println(indent(hint));
            }
            any = true;
        }
        if (!any) {
            out.println("nothing active or installed yet · try: jkit search jdk");
        }
        if (result.projectError() != null) {
            out.println("project file error: " + result.projectError());
        } else if (result.project().isPresent()) {
            ProjectSpec p = result.project().get();
            out.println("project: " + Changes.tilde(p.dir().toString()) + " (" + p.source() + ")");
            for (String warning : p.warnings()) {
                out.println("  warning: " + Changes.tilde(warning));
            }
        }
    }

    private static String row(String tool, String version, String source, String detail) {
        return String.format("%-9s%-24s%-10s%s", tool, version, source, detail).stripTrailing();
    }

    private static String indent(String hint) {
        return " ".repeat(9) + hint;
    }

    private static String label(Source source) {
        return source == Source.NONE ? "not set" : source.id();
    }

    /** {@code 21.0.9+10 temurin}, the release version of an outside JDK, or {@code -}. */
    private static String version(ToolState s) {
        if (s.active() != null) {
            return s.active().label();
        }
        if (s.current() == null) {
            return "-";
        }
        String release = s.tool().storeTool() == Tool.JDK ? ToolStates.releaseVersion(s.current()) : null;
        return release != null ? release : "?";
    }

    private static String detail(ToolState s, ToolStates.Result result) {
        return switch (s.source()) {
            case PROJECT -> projectFile(result) + " (" + pinText(s.pin()) + ")";
            case OUTSIDE, STALE -> s.tool().homeVariable() + "=" + Changes.tilde(s.current().toString());
            default -> "";
        };
    }

    /** One line saying what to do when the shell doesn't have what jkit would pick, else null. */
    static String hint(Context ctx, ToolState s, ToolStates.Result result) {
        Tool tool = s.tool();
        if (s.globalMissing() && s.wanted() != Source.SHELL && s.wanted() != Source.PROJECT) {
            return "global " + tool + " → " + Changes.tilde(s.global().toString())
                    + ", which no longer exists: jkit use " + tool + "@<version> --global";
        }
        if (s.matches()) {
            return s.source() == Source.SHELL && s.pin() != null && s.pinInstall() != null
                    && !s.pinInstall().home().equals(s.shellChoice())
                    ? projectFile(result) + " wants " + pinText(s.pin()) + "; this shell chose another with jkit use"
                            + ", to follow it: " + useCommand(tool, s.pinInstall(), false)
                    : null;
        }
        return switch (s.wanted()) {
            case SHELL -> "this shell chose " + Changes.tilde(s.shellChoice().toString()) + " with jkit use, but "
                    + tool.homeVariable() + " was changed since: jkit use " + tool + " again, or jkit reset " + tool;
            case PROJECT -> s.pinInstall() == null
                    ? projectFile(result) + " wants " + pinText(s.pin()) + ", not installed: " + installCommand(s.pin())
                    : projectFile(result) + " wants " + tool + " " + s.pinInstall().label() + "; this shell has "
                            + (s.source() == Source.NONE ? "none" : label(s.source())) + ": " + useCommand(tool, s.pinInstall(), false);
            case GLOBAL -> switch (s.source()) {
                case OUTSIDE -> tool.homeVariable() + " is set outside jkit and wins over the global " + tool
                        + "; remove it from your shell profile to use jkit's";
                case STALE -> "set by jkit earlier but no longer chosen: open a new shell, or jkit reset " + tool;
                default -> ctx.env.get("JKIT_SHELL") == null
                        ? "the global " + tool + " isn't applied: the jkit hook isn't loaded; add to ~/.bashrc:  eval \"$(jkit hook bash)\""
                        : "the global " + tool + " isn't applied in this shell yet: open a new shell";
            };
            case NONE -> s.source() == Source.STALE
                    ? "set by jkit earlier but no longer chosen: open a new shell, or jkit reset " + tool
                    : null;
            default -> null;
        };
    }

    private static String installedHint(Context ctx, Tool tool) throws Exception {
        List<Installed> installed = ctx.store.list(tool);
        Installed newest = installed.getFirst();
        return installed.size() + " installed, none chosen: " + useCommand(tool, newest, true);
    }

    private static String projectFile(ToolStates.Result result) {
        return result.project().map(ProjectSpec::source).orElse("project file");
    }

    /** {@code jdk = "21"}, {@code jdk = 21 zulu fx}. */
    private static String pinText(PinnedTool pin) {
        return pin.tool() + " " + (pin.version() == null ? "latest" : pin.version())
                + (pin.vendor() == null ? "" : " " + pin.vendor().name()) + (pin.fx() ? " fx" : "");
    }

    private static String useCommand(Tool tool, Installed i, boolean global) {
        return "jkit use " + tool + "@" + i.version().raw() + (i.vendor() == null ? "" : " " + i.vendor().name())
                + (i.fx() ? " --fx" : "") + (global ? " --global" : "");
    }

    private static String installCommand(PinnedTool pin) {
        return "jkit install " + pin.tool() + "@" + (pin.version() == null ? "latest" : pin.version())
                + (pin.vendor() == null ? "" : " " + pin.vendor().name()) + (pin.fx() ? " --fx" : "");
    }

    // JSON

    /**
     * {@code {schemaVersion, project: {dir, files, warnings} | null, projectError, tools: [{tool,
     * source, home, installed, version, pin, pinInstalled, wanted: {source, home}, global: {target,
     * missing}, matches, hint}]}}.
     */
    static Map<String, Object> json(Context ctx, ToolStates.Result result) throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("project", result.project().map(p -> (Object) JsonOut.fields(
                "dir", p.dir(), "files", p.files(), "warnings", p.warnings())).orElse(null));
        doc.put("projectError", result.projectError());
        List<Object> tools = new ArrayList<>();
        for (ToolState s : result.tools()) {
            tools.add(JsonOut.fields(
                    "tool", s.tool().id(),
                    "source", s.source().id(),
                    "home", s.current(),
                    "installed", s.active() == null ? null : JsonOut.installed(ctx, s.active()),
                    "version", s.active() != null ? s.active().version().raw()
                            : s.current() == null ? null : s.tool().storeTool() == Tool.JDK ? ToolStates.releaseVersion(s.current()) : null,
                    "pin", s.pin() == null ? null : JsonOut.fields(
                            "version", s.pin().version(),
                            "vendor", s.pin().vendor() == null ? null : s.pin().vendor().name(),
                            "fx", s.pin().fx()),
                    "pinInstalled", s.pinInstall() == null ? null : JsonOut.installed(ctx, s.pinInstall()),
                    "wanted", JsonOut.fields("source", s.wanted().id(), "home", s.wantedHome()),
                    "global", s.global() == null ? null : JsonOut.fields("target", s.global(), "missing", s.globalMissing()),
                    "matches", s.matches(),
                    "hint", hint(ctx, s, result)));
        }
        doc.put("tools", tools);
        return doc;
    }
}
