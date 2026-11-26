package dev.jkit.cli;

import dev.jkit.core.ToolStates;
import dev.jkit.model.Envs;
import dev.jkit.model.Visible;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "doctor",
        description = {"Checks your setup for problems and says how to fix them.",
                "Changes nothing.",
                "Exits 1 when a check fails. Uses the network only with --network."})
final class DoctorCommand implements Callable<Integer> {

    @Option(names = "--network", description = "Also check that foojay, services.gradle.org, Maven Central and GitHub (sbt) can be reached.")
    boolean network;

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    enum Level {
        OK, INFO, WARN, FAIL;

        String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One line of the report; {@code fix} is null when there is nothing to do. */
    record Finding(String id, Level level, String message, String fix) { }

    @Override
    public Integer call() throws Exception {
        Context ctx = Context.lenient(Envs.system(), System.out);
        List<Finding> findings = run(ctx, Path.of("").toAbsolutePath(), network);
        if (json.json) {
            ctx.out.println(JsonOut.write(json(findings)));
        } else {
            print(findings, ctx.out);
        }
        return findings.stream().anyMatch(f -> f.level() == Level.FAIL) ? 1 : 0;
    }

    // running the checks

    /** The checks in report order. Each is independent: one that throws becomes a fail line. */
    private static final List<Map.Entry<String, Check>> CHECKS = List.of(
            Map.entry("home", DoctorInstallChecks::home),
            Map.entry("hook", DoctorShellChecks::hook),
            Map.entry("binary", DoctorShellChecks::binary),
            Map.entry("config", DoctorInstallChecks::config),
            Map.entry("links", DoctorShellChecks::links),
            Map.entry("outside-vars", DoctorShellChecks::outsideVars),
            Map.entry("path-order", DoctorShellChecks::pathOrder),
            Map.entry("user-env", DoctorWindowsCheck::userEnv),
            Map.entry("store", DoctorInstallChecks::store),
            Map.entry("temp-files", DoctorInstallChecks::tempFiles),
            Map.entry("project", DoctorInstallChecks::project),
            Map.entry("index", DoctorInstallChecks::index),
            Map.entry("java-runs", DoctorInstallChecks::javaRuns),
            Map.entry("network", DoctorNetworkCheck::network));

    @FunctionalInterface
    interface Check {
        void run(DoctorReport r) throws Exception;
    }

    static List<Finding> run(Context ctx, Path cwd, boolean network) {
        DoctorReport r = new DoctorReport(ctx, cwd, network);
        try {
            r.states = ToolStates.read(ctx.env, cwd, ctx.home, ctx.store, ctx.config, ctx.os::readLink);
        } catch (Exception e) {
            r.statesError = String.valueOf(e.getMessage());
        }
        for (Map.Entry<String, Check> check : CHECKS) {
            r.id = check.getKey();
            try {
                check.getValue().run(r);
            } catch (Exception e) {
                r.fail("the check itself failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "please report this, with the output of jkit doctor --json");
            }
        }
        return List.copyOf(r.findings);
    }

    // output

    static void print(List<Finding> findings, PrintStream out) {
        for (Finding f : findings) {
            out.println(String.format("%-6s%s", f.level().id(), Visible.of(f.message())));
            if (f.fix() != null) {
                out.println("      fix: " + Visible.of(f.fix()));
            }
        }
        Map<Level, Integer> counts = counts(findings);
        int warns = counts.get(Level.WARN);
        int fails = counts.get(Level.FAIL);
        out.println(warns == 0 && fails == 0 ? "no problems found"
                : plural(warns, "warning") + ", " + plural(fails, "problem"));
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static Map<Level, Integer> counts(List<Finding> findings) {
        Map<Level, Integer> counts = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            counts.put(l, 0);
        }
        findings.forEach(f -> counts.merge(f.level(), 1, Integer::sum));
        return counts;
    }

    /** {@code {schemaVersion, checks: [{id, level, message, fix}], summary: {ok, info, warn, fail}}}. */
    static Map<String, Object> json(List<Finding> findings) {
        Map<String, Object> doc = JsonOut.document();
        List<Object> checks = new ArrayList<>();
        for (Finding f : findings) {
            checks.add(JsonOut.fields("id", f.id(), "level", f.level().id(), "message", f.message(), "fix", f.fix()));
        }
        doc.put("checks", checks);
        Map<String, Object> summary = JsonOut.fields();
        counts(findings).forEach((level, n) -> summary.put(level.id(), n));
        doc.put("summary", summary);
        return doc;
    }
}
