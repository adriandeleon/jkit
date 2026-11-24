package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * config.toml with environment overrides (JKIT_ + key in upper case, . and - as _), except
 * {@code shell.auto_switch}, whose override is {@code JKIT_AUTO_SWITCH} (1/true or 0/false).
 *
 * @param autoSwitch whether the shell hook switches to a project's versions on {@code cd}
 */
public record Config(String jdkVendor, String graalvmVendor, boolean autoSwitch) {
    public static final String AUTO_SWITCH_KEY = "shell.auto_switch";
    public static final String AUTO_SWITCH_ENV = "JKIT_AUTO_SWITCH";

    public Config(String jdkVendor, String graalvmVendor) {
        this(jdkVendor, graalvmVendor, false);
    }

    public static Config load(JkitHome home, Map<String, String> env) {
        String jdk = "temurin";
        String graalvm = "graalvm-ce";
        boolean autoSwitch = false;
        if (Files.isRegularFile(home.config())) {
            try {
                // by byte-order mark: Notepad and PowerShell 5 write one, and the TOML parser rejects it
                TomlParseResult toml = ProjectFile.parseToml(home.config(), ProjectFile.text(home.config()));
                if (toml.hasErrors()) {
                    throw new JkitException("invalid " + home.config() + ": " + toml.errors().getFirst());
                }
                jdk = valueOr(string(toml, "jdk.vendor", home), jdk);
                graalvm = valueOr(string(toml, "graalvm.vendor", home), graalvm);
                Object auto = toml.get(AUTO_SWITCH_KEY);
                if (auto instanceof Boolean b) {
                    autoSwitch = b;
                } else if (auto != null) {
                    throw new JkitException("invalid " + home.config() + ": " + AUTO_SWITCH_KEY
                            + " must be true or false, like " + AUTO_SWITCH_KEY.substring("shell.".length())
                            + " = true under [shell]");
                }
            } catch (IOException e) {
                throw new JkitException("cannot read " + home.config() + ": " + e.getMessage());
            }
        }
        jdk = valueOr(env.get("JKIT_JDK_VENDOR"), jdk);
        graalvm = valueOr(env.get("JKIT_GRAALVM_VENDOR"), graalvm);
        String autoEnv = env.get(AUTO_SWITCH_ENV);
        if (autoEnv != null && !autoEnv.isBlank()) {
            Boolean parsed = parseSwitch(autoEnv);
            if (parsed == null) {
                throw new JkitException(AUTO_SWITCH_ENV + "=" + autoEnv + " isn't 1 or 0",
                        "set it to 1 to switch versions on cd, 0 to turn that off");
            }
            autoSwitch = parsed;
        }
        return new Config(jdk, graalvm, autoSwitch);
    }

    /** 1/true/yes/on or 0/false/no/off, any case; null for anything else. */
    public static Boolean parseSwitch(String text) {
        return switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> null;
        };
    }

    /** The settings config.toml holds, for {@code jkit config}. */
    public enum Setting {
        JDK_VENDOR("jdk", "vendor", "JKIT_JDK_VENDOR", "temurin", "the vendor jdk@… uses when none is named"),
        GRAALVM_VENDOR("graalvm", "vendor", "JKIT_GRAALVM_VENDOR", "graalvm-ce",
                "the vendor graalvm@… uses when none is named"),
        AUTO_SWITCH("shell", "auto_switch", AUTO_SWITCH_ENV, "false", "switch to a project's versions on cd");

        public final String table;
        public final String name;
        public final String env;
        public final String fallback;
        public final String description;

        Setting(String table, String name, String env, String fallback, String description) {
            this.table = table;
            this.name = name;
            this.env = env;
            this.fallback = fallback;
            this.description = description;
        }

        /** {@code table.name}, as typed: {@code jdk.vendor}. */
        public String key() {
            return table + "." + name;
        }

        public boolean isSwitch() {
            return this == AUTO_SWITCH;
        }

        /** The setting for a typed key; null when there is none. */
        public static Setting of(String key) {
            for (Setting s : values()) {
                if (s.key().equals(key.trim().toLowerCase(java.util.Locale.ROOT))) {
                    return s;
                }
            }
            return null;
        }

        /** Its value in {@code config}, as text. */
        public String value(Config config) {
            return switch (this) {
                case JDK_VENDOR -> config.jdkVendor();
                case GRAALVM_VENDOR -> config.graalvmVendor();
                case AUTO_SWITCH -> Boolean.toString(config.autoSwitch());
            };
        }
    }

    private static String string(TomlParseResult toml, String key, JkitHome home) {
        Object value = toml.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new JkitException("invalid " + home.config() + ": " + key + " must be a string, like "
                + key + " = \"temurin\"");
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public Vendor defaultVendor(Tool tool) {
        return switch (tool) {
            case JDK -> Vendor.of(jdkVendor);
            case GRAALVM -> Vendor.of(graalvmVendor);
            default -> null;
        };
    }
}
