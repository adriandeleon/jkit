package dev.jkit.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A JDK vendor: the name users type plus foojay's distribution name.
 * Unknown names pass through, so a vendor foojay adds works at once.
 */
public record Vendor(String name, String foojayName) {
    /** Typed name to foojay name. Only the first four are shown back to users as names. */
    private static final Map<String, String> ALIASES = Map.of(
            "sapmachine", "sap_machine",
            "graalvm-ce", "graalvm_community",
            "liberica-nik", "liberica_native",
            "openjdk", "oracle_open_jdk",
            "adoptium", "temurin");
    private static final Map<String, String> FRIENDLY = ALIASES.entrySet().stream()
            .filter(e -> !e.getKey().equals("adoptium"))
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    /** Retired distributions foojay still lists; hidden from search and resolution. */
    private static final Set<String> RETIRED = Set.of(
            "aoj", "aoj_openj9", "ojdk_build", "graalvm_ce8", "graalvm_ce11", "graalvm_ce16",
            "graalvm_ce17", "graalvm_ce19");
    private static final Set<String> GRAALVM_BASED =
            Set.of("graalvm_community", "graalvm", "liberica_native", "mandrel");

    public static Vendor of(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        String foojay = ALIASES.getOrDefault(lower, lower.replace('-', '_'));
        return fromFoojay(foojay);
    }

    public static Vendor fromFoojay(String foojayName) {
        String name = FRIENDLY.getOrDefault(foojayName, foojayName.replace('_', '-'));
        return new Vendor(name, foojayName);
    }

    /** True for distributions that include native-image (usable as GRAALVM_HOME). */
    public boolean isGraalVmBased() { return GRAALVM_BASED.contains(foojayName); }

    public boolean isRetired() { return RETIRED.contains(foojayName); }

    public static String graalVmVendorList() { return "graalvm-ce, graalvm, liberica-nik, mandrel"; }

    @Override
    public String toString() { return name; }
}
