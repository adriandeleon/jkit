package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/** config.toml with environment overrides (JKIT_ + key in upper case, . and - as _). */
public record Config(String jdkVendor, String graalvmVendor) {

    public static Config load(JkitHome home, Map<String, String> env) {
        String jdk = "temurin";
        String graalvm = "graalvm-ce";
        if (Files.isRegularFile(home.config())) {
            try {
                TomlParseResult toml = Toml.parse(home.config());
                if (toml.hasErrors()) {
                    throw new JkitException("invalid " + home.config() + ": " + toml.errors().getFirst());
                }
                jdk = valueOr(toml.getString("jdk.vendor"), jdk);
                graalvm = valueOr(toml.getString("graalvm.vendor"), graalvm);
            } catch (IOException e) {
                throw new JkitException("cannot read " + home.config() + ": " + e.getMessage());
            }
        }
        jdk = valueOr(env.get("JKIT_JDK_VENDOR"), jdk);
        graalvm = valueOr(env.get("JKIT_GRAALVM_VENDOR"), graalvm);
        return new Config(jdk, graalvm);
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
