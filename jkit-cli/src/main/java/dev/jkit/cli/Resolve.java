package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import java.io.IOException;
import java.util.List;
import java.util.TreeSet;

/** Vendor and installed-version rules shared by install, use and remove. */
final class Resolve {
    private Resolve() { }

    /** The vendor typed, or the default; an error for tools without vendors. */
    static Vendor vendor(Context ctx, ToolSpec spec, String typed) {
        Tool tool = spec.tool();
        if (!tool.hasVendors()) {
            if (typed != null) {
                throw new JkitException(tool + " has no vendors; drop '" + typed + "'");
            }
            return null;
        }
        Vendor vendor = typed == null ? ctx.config.defaultVendor(tool) : Vendor.of(typed);
        if (tool == Tool.GRAALVM && !vendor.isGraalVmBased()) {
            throw new JkitException(vendor + " is not GraalVM-based",
                    "graalvm vendors: " + Vendor.graalVmVendorList());
        }
        return vendor;
    }

    /**
     * Like {@link #vendor}, then checks the name against foojay's vendor list (cached), so a typo
     * gets "did you mean" instead of a failed request. For commands that go online anyway.
     */
    static Vendor knownVendor(Context ctx, ToolSpec spec, String typed) throws IOException {
        Vendor vendor = vendor(ctx, spec, typed);
        if (vendor == null) {
            return null;
        }
        String source = typed != null ? ""
                : spec.tool() == Tool.GRAALVM ? "your default GraalVM vendor (graalvm.vendor)"
                : "your default JDK vendor (jdk.vendor)";
        Vendor known = ctx.foojay().requireKnown(vendor, typed != null ? typed : vendor.name(), source);
        return vendor(ctx, spec, known.name());
    }

    /** Installed matches, newest first; exit code 3 with the install command when none. */
    static List<Installed> installed(Context ctx, ToolSpec spec, Vendor vendor, String typedVendor, boolean fx)
            throws IOException {
        List<Installed> matches = ctx.store.find(spec.tool(), spec.version(), vendor);
        if (!matches.isEmpty()) {
            return matches;
        }
        String what = spec.tool() == Tool.JDK || spec.tool() == Tool.GRAALVM ? spec.tool().id().toUpperCase() : spec.tool().id();
        String versionText = spec.version() == null ? "any version" : spec.version();
        String vendorText = vendor == null ? ""
                : typedVendor == null ? " from " + vendor + " (default vendor)" : " " + vendor;
        String install = "jkit install " + spec + (typedVendor == null ? "" : " " + typedVendor) + (fx ? " --fx" : "");
        List<String> hints = new java.util.ArrayList<>();
        hints.add("install it:          " + install);
        if (vendor != null) {
            TreeSet<String> others = new TreeSet<>();
            for (Installed i : ctx.store.find(spec.tool(), spec.version(), null)) {
                others.add(i.vendor().name());
            }
            for (String other : others) {
                hints.add("or use installed:    jkit use " + spec + " " + other);
            }
        }
        throw new JkitException(JkitException.NOT_INSTALLED,
                "no installed " + what + " matches " + versionText + vendorText, hints.toArray(String[]::new));
    }
}
