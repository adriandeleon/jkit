package dev.jkit.cli;

import dev.jkit.core.Fs;
import dev.jkit.core.Installed;
import dev.jkit.core.ToolState;
import dev.jkit.core.ToolState.Source;
import dev.jkit.core.ToolStates;
import dev.jkit.model.ProjectSpec;
import dev.jkit.model.Tool;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Doctor's checks of this shell: the hook, PATH and the *_HOME variables. */
final class DoctorShellChecks {
    private DoctorShellChecks() { }

    static void hook(DoctorReport r) {
        String shell = r.ctx.env.get("JKIT_SHELL");
        if (shell != null && !shell.isBlank()) {
            if (r.ctx.env.get("JKIT_ENV_FILE") == null) {
                // the hook exports JKIT_SHELL, so subshells and scripts inherit it without the function
                r.warn("JKIT_SHELL=" + shell + " is inherited, but the jkit function isn't defined here "
                                + "(a subshell or script?), so jkit use can't change this shell",
                        "load the hook in this shell too, or run jkit from your interactive shell");
                return;
            }
            r.ok("shell hook loaded (" + shell + ")");
            return;
        }
        String name = Path.of(r.ctx.env.getOrDefault("SHELL", "bash")).getFileName().toString();
        String fix = switch (name) {
            case "zsh" -> "jkit hook zsh --install, then open a new shell";
            case "fish" -> "jkit hook fish --install, then open a new shell";
            default -> Changes.HOOK_SETUP + ", then open a new shell";
        };
        r.warn("shell hook not loaded: jkit use can't change this shell, and the global versions aren't on PATH", fix);
    }

    static void binary(DoctorReport r) throws IOException {
        List<Path> found = which(r.ctx, "jkit");
        if (found.isEmpty()) {
            r.warn("jkit isn't on PATH", "put the folder holding the jkit binary on PATH");
        } else if (found.size() == 1) {
            r.ok("jkit on PATH: " + r.tilde(found.getFirst()));
        } else {
            r.warn(found.size() + " different jkit binaries on PATH, the first wins: "
                    + String.join(", ", found.stream().map(r::tilde).toList()),
                    "remove the ones you don't use, e.g. an old copy in /usr/local/bin");
        }
    }

    static void links(DoctorReport r) throws IOException {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        boolean any = false;
        for (ToolState s : states.tools()) {
            if (s.globalMissing()) {
                any = true;
                List<Installed> installed = r.ctx.store.list(s.tool());
                String fix = installed.isEmpty()
                        ? "install one and make it global: jkit install " + s.tool() + "@<version>, then jkit use … --global;"
                                + " or remove the link: " + (DoctorReport.WINDOWS ? "Remove-Item " : "rm ") + r.arg(r.ctx.home.global(s.tool()))
                        : StatusCommand.useCommand(s.tool(), installed.getFirst(), true) + ", or remove the link: "
                                + (DoctorReport.WINDOWS ? "Remove-Item " : "rm ") + r.arg(r.ctx.home.global(s.tool()));
                r.fail("global " + s.tool() + " → " + r.tilde(s.global()) + ", which no longer exists", fix);
            }
            if (s.shellChoice() != null && !Files.isDirectory(s.shellChoice())) {
                any = true;
                r.fail("this shell's " + s.tool() + " (from jkit use) → " + r.tilde(s.shellChoice()) + ", which no longer exists",
                        "jkit reset " + s.tool());
            }
        }
        if (!any) {
            r.ok("global links and this shell's choices point at existing installs");
        }
    }

    static void outsideVars(DoctorReport r) {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        boolean any = false;
        for (ToolState s : states.tools()) {
            String variable = s.tool().homeVariable() + "=" + r.tilde(s.current());
            if (s.source() == Source.OUTSIDE && s.wanted() != Source.NONE) {
                any = true;
                String loser = switch (s.wanted()) {
                    case SHELL -> "this shell's jkit use";
                    case PROJECT -> states.project().map(ProjectSpec::source).orElse("the project file") + "'s " + s.tool();
                    default -> "the global " + s.tool();
                };
                r.warn(variable + " is set outside jkit and wins over " + loser,
                        "remove the export from your shell profile (~/.bashrc, ~/.zshrc, ~/.profile)");
            } else if (s.source() == Source.STALE) {
                any = true;
                r.warn(variable + " is a jkit install nothing chooses any more", "open a new shell, or jkit reset " + s.tool());
            } else if (s.source() == Source.OUTSIDE) {
                any = true;
                r.info(variable + " is set outside jkit; jkit has no " + s.tool() + " chosen here, so nothing conflicts");
            }
        }
        if (!any) {
            r.ok("no *_HOME variable set outside jkit");
        }
    }

    private static final Map<Tool, String> COMMANDS = Map.of(Tool.JDK, "java", Tool.MAVEN, "mvn", Tool.GRADLE, "gradle", Tool.SBT, "sbt");

    static void pathOrder(DoctorReport r) throws IOException {
        ToolStates.Result states = r.states();
        if (states == null) {
            return;
        }
        List<String> good = new ArrayList<>();
        for (Tool tool : List.of(Tool.JDK, Tool.MAVEN, Tool.GRADLE, Tool.SBT)) {
            ToolState s = states.get(tool);
            if (s.current() == null || !(s.source() == Source.SHELL || s.source() == Source.PROJECT || s.source() == Source.GLOBAL)) {
                continue; // jkit doesn't manage this tool in this shell
            }
            String command = COMMANDS.get(tool);
            // compare folders: on Windows the command is java.exe or mvn.cmd, not plain java or mvn
            Path expectedBin = s.current().resolve("bin");
            List<Path> found = which(r.ctx, command);
            if (found.isEmpty()) {
                r.warn("'" + command + "' isn't on PATH, though " + tool.homeVariable() + " is set by jkit",
                        "open a new shell; if that doesn't help, load the jkit hook after other PATH changes");
            } else if (!Fs.same(found.getFirst().getParent(), expectedBin)) {
                r.warn("'" + command + "' on PATH is " + r.tilde(found.getFirst()) + ", not $" + tool.homeVariable()
                                + "/bin/" + command,
                        "load the jkit hook after other PATH changes in your shell profile, or remove the other " + command);
            } else {
                good.add(command);
            }
        }
        if (!good.isEmpty()) {
            r.ok(String.join(", ", good) + " on PATH " + (good.size() == 1 ? "comes" : "come") + " from jkit's versions");
        }
    }

    /** Every distinct executable named {@code name} on PATH, in PATH order (links followed). */
    static List<Path> which(Context ctx, String name) throws IOException {
        List<String> names = ctx.platform.isWindows()
                ? List.of(name + ".exe", name + ".cmd", name + ".bat", name)
                : List.of(name);
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> found = new ArrayList<>();
        for (String entry : ctx.env.getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            for (String n : names) {
                Path candidate = Path.of(entry).resolve(n);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    if (seen.add(candidate.toRealPath())) {
                        found.add(candidate);
                    }
                    break;
                }
            }
        }
        return found;
    }
}
