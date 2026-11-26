package dev.jkit.cli;

import dev.jkit.core.Config;
import dev.jkit.core.Config.Setting;
import dev.jkit.core.ConfigWriter;
import dev.jkit.core.TextFiles;
import dev.jkit.model.Envs;
import dev.jkit.model.JkitException;
import dev.jkit.model.Suggest;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "config", description = {"Shows and changes the settings in config.toml, in jkit's home.",
        "With no subcommand, lists every setting, its value and where that comes from.",
        "Keys: jdk.vendor, graalvm.vendor, shell.auto_switch."},
        subcommands = {ConfigCommand.ListSettings.class, ConfigCommand.Get.class, ConfigCommand.Set.class,
            ConfigCommand.Unset.class})
final class ConfigCommand implements Callable<Integer> {

    @Mixin
    JsonOut.Flag json = new JsonOut.Flag();

    @Override
    public Integer call() throws Exception {
        return list(Context.readOnly(), json.json);
    }

    /** Where a setting's value comes from: the environment wins over config.toml, which wins over the default. */
    record Effective(Setting setting, String value, String source, String inFile, String inEnv) { }

    static List<Effective> effective(Context ctx) {
        Map<String, Object> file = fileValues(ctx);
        List<Effective> all = new ArrayList<>();
        for (Setting s : Setting.values()) {
            Object raw = file.get(s.key());
            String inFile = raw == null ? null : raw.toString();
            String inEnv = ctx.env.get(s.env);
            inEnv = inEnv == null || inEnv.isBlank() ? null : inEnv;
            // a config.toml jkit can't use counts for nothing: its values aren't the ones applied
            String source = inEnv != null ? "env" : inFile != null && ctx.configError == null ? "file" : "default";
            all.add(new Effective(s, s.value(ctx.config), source, inFile, inEnv));
        }
        return all;
    }

    /** config.toml's own values by dotted key; empty when there is no file. */
    private static Map<String, Object> fileValues(Context ctx) {
        return Config.fileValues(ctx.home);
    }

    static int list(Context ctx, boolean asJson) {
        List<Effective> all = effective(ctx);
        String file = Changes.tilde(ctx.home.config().toString());
        if (asJson) {
            Map<String, Object> doc = JsonOut.document();
            doc.put("file", ctx.home.config());
            doc.put("error", ctx.configError);
            doc.put("settings", all.stream().map(ConfigCommand::json).toList());
            ctx.out.println(JsonOut.write(doc));
            return 0;
        }
        int keyWidth = all.stream().mapToInt(e -> e.setting().key().length()).max().orElse(0);
        int valueWidth = all.stream().mapToInt(e -> e.value().length()).max().orElse(0);
        for (Effective e : all) {
            ctx.out.println(String.format("%-" + keyWidth + "s  %-" + valueWidth + "s  %s",
                    e.setting().key(), e.value(), from(e, file)));
        }
        if (ctx.configError != null) {
            ctx.out.println();
            ctx.out.println("(" + ctx.configError + ", so these are the defaults)");
        }
        return 0;
    }

    private static String from(Effective e, String file) {
        return switch (e.source()) {
            case "env" -> "from " + e.setting().env
                    + (e.inFile() == null ? "" : " (" + file + " says " + e.inFile() + ")");
            case "file" -> "from " + file;
            default -> "default";
        };
    }

    private static Map<String, Object> json(Effective e) {
        return JsonOut.fields("key", e.setting().key(), "value", e.value(), "source", e.source(),
                "file", e.inFile(), "env", e.inEnv(), "envVariable", e.setting().env, "default", e.setting().fallback,
                "description", e.setting().description);
    }

    static Setting setting(String key) {
        Setting s = Setting.of(key);
        if (s == null) {
            List<String> keys = Arrays.stream(Setting.values()).map(Setting::key).toList();
            String guess = Suggest.closest(key, keys);
            // a bad argument, like an unknown vendor in config set jdk.vendor
            throw new JkitException(CommandLine.ExitCode.USAGE, "unknown setting '" + key + "'",
                    guess != null ? "did you mean " + guess + "?" : "settings: " + String.join(", ", keys));
        }
        return s;
    }

    /** Setting names for completion. */
    static final class Keys implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return Arrays.stream(Setting.values()).map(Setting::key).iterator();
        }
    }

    @Command(name = "list", description = "Lists every setting, its value and where that comes from.")
    static final class ListSettings implements Callable<Integer> {
        @Mixin
        JsonOut.Flag json = new JsonOut.Flag();

        @Override
        public Integer call() {
            return list(Context.readOnly(), json.json);
        }
    }

    @Command(name = "get", description = {"Prints one setting's value, as jkit uses it.",
            "An environment variable wins over config.toml, which wins over the default."})
    static final class Get implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<key>", completionCandidates = Keys.class)
        String key;

        @Mixin
        JsonOut.Flag json = new JsonOut.Flag();

        @Override
        public Integer call() {
            return run(Context.readOnly());
        }

        int run(Context ctx) {
            Setting s = setting(key);
            Effective e = effective(ctx).stream().filter(x -> x.setting() == s).findFirst().orElseThrow();
            if (json.json) {
                Map<String, Object> doc = JsonOut.document();
                doc.putAll(json(e));
                ctx.out.println(JsonOut.write(doc));
            } else {
                ctx.out.println(e.value());
            }
            return 0;
        }
    }

    @Command(name = "set", description = {"Sets a value in config.toml, keeping comments and order.",
            "jkit config set jdk.vendor zulu",
            "jkit config set shell.auto_switch true"})
    static final class Set implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<key>", completionCandidates = Keys.class)
        String key;

        @Parameters(index = "1", paramLabel = "<value>")
        String value;

        @Mixin
        JsonOut.Flag json = new JsonOut.Flag();

        @Override
        public Integer call() throws Exception {
            return run(Context.forConfigEdit(Envs.system(), System.out, false));
        }

        int run(Context ctx) throws Exception {
            Setting s = setting(key);
            PrintStream say = json.json ? ctx.err : ctx.out;
            Changes report = new Changes(ctx);
            String shown = check(ctx, s, value, report);
            String literal = s.isSwitch() ? shown : "\"" + shown + "\"";
            boolean changed = write(ctx, ConfigWriter::set, s, literal, report);
            String file = Changes.tilde(ctx.home.config().toString());
            say.println(changed ? "set " + s.key() + " = " + literal + " in " + file
                    : s.key() + " is already " + literal + " in " + file + "; nothing changed");
            if (changed) {
                after(ctx, s, report);
            }
            overridden(ctx, s, report);
            report.print(say);
            if (json.json) {
                done(ctx, s, changed, report);
            }
            return 0;
        }
    }

    @Command(name = "unset", description = {"Removes a setting from config.toml: the default applies.",
            "A table left empty goes too; comments and the other settings stay."})
    static final class Unset implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "<key>", completionCandidates = Keys.class)
        String key;

        @Mixin
        JsonOut.Flag json = new JsonOut.Flag();

        @Override
        public Integer call() throws Exception {
            return run(Context.forConfigEdit(Envs.system(), System.out, true));
        }

        int run(Context ctx) throws Exception {
            Setting s = setting(key);
            PrintStream say = json.json ? ctx.err : ctx.out;
            Changes report = new Changes(ctx);
            boolean changed = Files.isRegularFile(ctx.home.config())
                    && write(ctx, (file, text, table, name, ignored) -> ConfigWriter.unset(file, text, table, name),
                            s, null, report);
            String file = Changes.tilde(ctx.home.config().toString());
            say.println(changed ? "removed " + s.key() + " from " + file + "; the default, " + s.fallback + ", applies"
                    : file + " doesn't set " + s.key() + "; nothing changed");
            if (changed) {
                after(ctx, s, report);
            }
            overridden(ctx, s, report);
            report.print(say);
            if (json.json) {
                done(ctx, s, changed, report);
            }
            return 0;
        }
    }

    /** {@link ConfigWriter#set} or {@link ConfigWriter#unset}. */
    @FunctionalInterface
    interface Writer {
        ConfigWriter.Edit edit(Path file, String text, String table, String key, String value);
    }

    /** Applies the edit to config.toml, creating it (and the jkit home) if needed; true when the file changed. */
    private static boolean write(Context ctx, Writer writer, Setting s, String literal, Changes report)
            throws IOException {
        Path config = ctx.home.config();
        boolean exists = Files.exists(config);
        ConfigWriter.Edit edit = writer.edit(config, exists ? TextFiles.text(config) : "", s.table, s.name, literal);
        if (!edit.changed()) {
            return false;
        }
        HookCommand.createParent(ctx, config, report);
        // all at once, through a dotfile manager's link; a new file is 644 whatever the umask
        TextFiles.write(config, edit.text(), true);
        report.file(config, !exists);
        return true;
    }

    /**
     * The value as it goes into config.toml: {@code true}/{@code false} for a switch, the vendor's
     * own name for a vendor (checked against foojay for jdk.vendor when it can be reached).
     */
    static String check(Context ctx, Setting s, String value, Changes report) {
        if (s.isSwitch()) {
            Boolean on = Config.parseSwitch(value);
            if (on == null) {
                throw new JkitException(CommandLine.ExitCode.USAGE, s.key() + " must be true or false, not '" + value + "'");
            }
            return on.toString();
        }
        Vendor vendor = Vendor.of(value);
        JkitException notAVendor = new JkitException(CommandLine.ExitCode.USAGE, "'" + value + "' isn't a vendor name", "see the vendors: jkit search jdk");
        if (value.isBlank()) {
            throw notAVendor;
        }
        // A name like "SAP Machine" isn't safe as typed, but foojay may know it as a synonym, as
        // jkit install does; only foojay's answer can make it usable.
        boolean safe = Vendor.isSafeName(vendor.foojayName());
        if (s == Setting.GRAALVM_VENDOR) {
            if (!safe) {
                throw notAVendor;
            }
            if (!vendor.isGraalVmBased()) {
                throw new JkitException(CommandLine.ExitCode.USAGE, vendor.name() + " doesn't include native-image, so it can't be the GraalVM vendor",
                        "GraalVM vendors: " + Vendor.graalVmVendorList());
            }
            return vendor.name();
        }
        try {
            return ctx.foojay().requireKnown(vendor, value, "").name();
        } catch (IOException | JkitException e) {
            if (e instanceof JkitException je && !je.getMessage().startsWith("--offline")) {
                throw je; // foojay answered: not a vendor it knows
            }
            if (!safe) {
                throw notAVendor;
            }
            report.note("couldn't check " + vendor.name() + " against foojay's vendor list (" + e.getMessage()
                    + "); set anyway");
            return vendor.name();
        }
    }

    /** When the change takes effect, said in the terms of what it changes. */
    private static void after(Context ctx, Setting s, Changes report) {
        Config now = Config.load(ctx.home, Map.of());
        switch (s) {
            case JDK_VENDOR -> report.note("jdk@… without a vendor now means " + now.jdkVendor()
                    + "; installed JDKs and the global default are unchanged");
            case GRAALVM_VENDOR -> report.note("graalvm@… without a vendor now means " + now.graalvmVendor()
                    + "; installed builds and the global default are unchanged");
            case AUTO_SWITCH -> report.note(Changes.shellWithoutAutoSwitch(ctx) instanceof String shell
                    ? (now.autoSwitch()
                            ? shell + " doesn't switch on cd yet, so this changes nothing in it (its hook warns"
                                    + " about it); jkit sync applies a project's versions there. bash, zsh and fish"
                                    + " switch on cd"
                            : shell + " doesn't switch on cd yet, so this changes nothing in it")
                    : now.autoSwitch()
                    ? "new shells switch to a project's versions on cd (with the hook: " + Changes.HOOK_SETUP
                            + "); this shell doesn't until you open a new one"
                    : "new shells no longer switch on cd; this shell still does until you open a new one"
                            + " (or: export " + Config.AUTO_SWITCH_ENV + "=0)");
        }
    }

    /** A variable set for this setting wins over config.toml: say so, or the change looks like it did nothing. */
    private static void overridden(Context ctx, Setting s, Changes report) {
        String env = ctx.env.get(s.env);
        if (env != null && !env.isBlank()) {
            report.note("warning: " + s.env + "=" + env + " is set and wins over config.toml; unset it for this to apply");
        }
    }

    private static void done(Context ctx, Setting s, boolean changed, Changes report) {
        Context reread = Context.forEnv(ctx.env, ctx.out);
        Effective e = effective(reread).stream().filter(x -> x.setting() == s).findFirst().orElseThrow();
        Map<String, Object> doc = JsonOut.document();
        doc.putAll(json(e));
        doc.put("changed", changed);
        doc.put("changes", report.json());
        doc.put("notes", report.notes());
        ctx.out.println(JsonOut.write(doc));
    }
}
