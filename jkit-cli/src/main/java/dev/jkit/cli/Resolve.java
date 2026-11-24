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

    /**
     * Whether two paths name the same folder: equal as written, or the same file on disk. Windows
     * reads a junction back with long names (runneradmin) where jkit was given short ones (RUNNER~1),
     * so comparing the text alone says a global link points elsewhere when it doesn't.
     */
    static boolean same(java.nio.file.Path a, java.nio.file.Path b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())) {
            return true;
        }
        try {
            return java.nio.file.Files.exists(a) && java.nio.file.Files.exists(b) && java.nio.file.Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code jdk@lts} as a feature release: online, the newest GA LTS foojay lists; offline (use,
     * exec, remove, upgrade), the newest LTS release installed. Any other spec is returned as is.
     */
    static ToolSpec lts(Context ctx, ToolSpec spec, boolean online) throws Exception {
        if (spec == null || !spec.isLts()) {
            return spec;
        }
        if (online) {
            for (dev.jkit.sources.FoojaySource.Major m : ctx.foojay().majors()) {
                if (m.isLts()) {
                    return new ToolSpec(spec.tool(), String.valueOf(m.major()));
                }
            }
            throw new JkitException("foojay lists no LTS release");
        }
        return lts(ctx, spec, null, false);
    }

    /**
     * Offline {@code lts}: the newest installed LTS release that {@link #installed} would accept with
     * this vendor ({@code typedVendor}, else the default) and {@code fx}, so {@code jkit use jdk@lts zulu}
     * doesn't pick a release only another vendor has.
     */
    static ToolSpec lts(Context ctx, ToolSpec spec, String typedVendor, boolean fx) throws Exception {
        if (spec == null || !spec.isLts()) {
            return spec;
        }
        java.util.TreeSet<Integer> majors = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        for (dev.jkit.core.Installed i : ctx.store.list(spec.tool().storeTool())) {
            int major = JsonOut.major(i.tool(), i.version());
            boolean graal = i.vendor() != null && i.vendor().isGraalVmBased();
            if (ToolSpec.isLtsMajor(major) && (spec.tool() != dev.jkit.model.Tool.GRAALVM || graal)) {
                majors.add(major);
            }
        }
        if (majors.isEmpty()) {
            throw new JkitException(JkitException.NOT_INSTALLED, "no LTS " + spec.tool() + " is installed",
                    "install one: jkit install " + spec.tool() + "@lts");
        }
        for (int major : majors) {
            ToolSpec candidate = new ToolSpec(spec.tool(), String.valueOf(major));
            try {
                installed(ctx, candidate, vendor(ctx, candidate, typedVendor), typedVendor, fx);
                return candidate;
            } catch (JkitException notThisOne) {
                // try the next older LTS
            }
        }
        return new ToolSpec(spec.tool(), String.valueOf(majors.first())); // the caller says what is missing
    }

    /** The vendor typed, or the default; an error for tools without vendors. */
    static Vendor vendor(Context ctx, ToolSpec spec, String typed) {
        Tool tool = spec.tool();
        if (!tool.hasVendors()) {
            if (typed != null) {
                throw new JkitException(picocli.CommandLine.ExitCode.USAGE, tool + " has no vendors; drop '" + typed + "'",
                        dev.jkit.model.Version.looksLikeVersion(typed)
                                ? "a version goes after @, not a space: " + tool + "@" + typed.trim()
                                : "e.g. jkit install " + tool + "@<version>");
            }
            return null;
        }
        Vendor vendor = typed == null ? ctx.config.defaultVendor(tool) : Vendor.of(typed);
        if (tool == Tool.GRAALVM && !vendor.isGraalVmBased()) {
            throw new JkitException(typed != null ? picocli.CommandLine.ExitCode.USAGE : JkitException.GENERAL,
                    vendor + " is not GraalVM-based",
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

    /**
     * Installed matches, newest first; exit code 3 with the install command when none. With
     * {@code fx} only JavaFX builds match; without it plain builds win, and JavaFX builds match
     * only when no plain build does.
     */
    static List<Installed> installed(Context ctx, ToolSpec spec, Vendor vendor, String typedVendor, boolean fx)
            throws IOException {
        return installed(ctx, spec, vendor, typedVendor, fx, "use");
    }

    /** Like above; {@code command} (use, remove or exec) is what the hints suggest running again. */
    static List<Installed> installed(Context ctx, ToolSpec spec, Vendor vendor, String typedVendor, boolean fx,
                                     String command) throws IOException {
        List<Installed> all = ctx.store.find(spec.tool(), spec.version(), vendor);
        List<Installed> plain = all.stream().filter(i -> !i.fx()).toList();
        List<Installed> withFx = all.stream().filter(Installed::fx).toList();
        List<Installed> matches = fx ? withFx : plain.isEmpty() ? withFx : plain;
        if (!matches.isEmpty()) {
            return matches;
        }
        String what = spec.tool() == Tool.JDK || spec.tool() == Tool.GRAALVM ? spec.tool().id().toUpperCase() : spec.tool().id();
        String versionText = spec.version() == null ? "any version" : spec.version();
        String vendorText = (fx ? " with JavaFX" : "") + (vendor == null ? ""
                : typedVendor == null ? " from " + vendor + " (default vendor)" : " " + vendor);
        String install = "jkit install " + spec + (typedVendor == null ? "" : " " + typedVendor) + (fx ? " --fx" : "");
        List<String> hints = new java.util.ArrayList<>();
        if (!command.equals("remove")) {
            hints.add("install it:          " + install);
        }
        if (vendor != null) {
            TreeSet<String> others = new TreeSet<>();
            for (Installed i : ctx.store.find(spec.tool(), spec.version(), null)) {
                if (i.fx() == fx || !fx) { // with --fx, only vendors that have an fx build
                    others.add(i.vendor().name());
                }
            }
            for (String other : others) {
                hints.add((command.equals("remove") ? "installed:           " : "or use installed:    ") + "jkit "
                        + command + " " + spec + " " + other + (fx ? " --fx" : "")
                        + (command.equals("exec") ? " -- <command>" : ""));
            }
        }
        throw new JkitException(JkitException.NOT_INSTALLED,
                "no installed " + what + " matches " + versionText + vendorText, hints.toArray(String[]::new));
    }

    /**
     * Points a global link at an install while holding the link lock, after checking the install is
     * still there: a jkit remove running at the same time must not leave the link on a deleted folder.
     */
    @SuppressWarnings("try")
    static void pointGlobal(Context ctx, java.nio.file.Path link, java.nio.file.Path target) throws Exception {
        try (AutoCloseable lock = ctx.store.linkLock()) {
            if (!java.nio.file.Files.isRegularFile(target.resolve(dev.jkit.core.Store.MARKER),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new JkitException(Changes.tilde(target.toString()) + " was removed by another jkit meanwhile",
                        "nothing changed; install it again, or pick another version");
            }
            ctx.os.pointLink(link, target);
        }
    }
}
