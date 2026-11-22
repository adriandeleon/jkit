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
                TomlParseResult toml = Toml.parse(ProjectFile.text(home.config()));
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
            autoSwitch = switch (autoEnv.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "1", "true", "yes", "on" -> true;
                case "0", "false", "no", "off" -> false;
                default -> throw new JkitException(AUTO_SWITCH_ENV + "=" + autoEnv + " isn't 1 or 0",
                        "set it to 1 to switch versions on cd, 0 to turn that off");
            };
        }
        return new Config(jdk, graalvm, autoSwitch);
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
