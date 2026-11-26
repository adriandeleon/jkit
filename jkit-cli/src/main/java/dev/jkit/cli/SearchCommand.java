package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.core.Signatures;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import dev.jkit.sources.FoojaySource;
import dev.jkit.sources.FoojaySource.Behind;
import dev.jkit.sources.FoojaySource.Major;
import dev.jkit.sources.FoojaySource.Pkg;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "search", description = {"Lists the versions available to install.",
        "Online, cached for 24 h; --offline uses the cache.",
        "",
        "  jkit search jdk                  newest build per vendor and release",
        "  jkit search jdk@21 zulu          every Zulu 21 build",
        "  jkit search maven@3.9"})
final class SearchCommand implements Callable<Integer> {
    private static final String INSTALLED = " ●";

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class,
            description = "jdk, maven, gradle, sbt or graalvm, optionally with a version prefix.")
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<vendor>", completionCandidates = Candidates.Vendors.class, description = "JDK vendor, e.g. temurin.")
    String vendorText;

    @Option(names = "--fx", description = "JavaFX builds.")
    boolean fx;

    @Option(names = "--pre", description = "Include pre-releases.")
    boolean pre;

    @Option(names = "--maintained", description = "Hide JDK versions that are no longer maintained.")
    boolean maintained;

    @CommandLine.Mixin
    JsonOut.Flag jsonFlag = new JsonOut.Flag();

    @Mixin
    Options options;

    @Override
    public Integer call() throws Exception {
        return run(options.context());
    }

    int run(Context ctx) throws Exception {
        // a vendor's own newest LTS; with none named, the newest of any vendor, as the list shows them all
        spec = Resolve.lts(ctx, spec, true, spec != null && spec.isLts() && vendorText != null
                ? Resolve.knownVendor(ctx, spec, vendorText) : null, fx);
        if (jsonFlag.json) {
            Map<String, Object> doc = json(ctx);
            doc.put("changes", new Changes(ctx).disk().json());
            ctx.out.println(JsonOut.write(doc));
            return 0;
        }
        switch (spec.tool()) {
            case JDK, GRAALVM -> searchJdk(ctx);
            case MAVEN -> grouped(ctx, ctx.maven().versions(pre), () -> ctx.maven().versions(true));
            case GRADLE -> grouped(ctx, ctx.gradle().versions(pre), () -> ctx.gradle().versions(true));
            case SBT -> grouped(ctx, ctx.sbt().versions(pre), () -> ctx.sbt().versions(true));
        }
        // stdout is the table; what the lookup wrote to disk goes to stderr
        new Changes(ctx).disk().print(ctx.err);
        return 0;
    }

    /**
     * {@code tool}, {@code defaultVendor} and {@code majors} (JDKs only: major, lts, maintained) and
     * {@code available}: for a JDK with no version, the newest build per vendor and major; with a
     * version and no vendor, the newest matching build per vendor; with a vendor, every matching
     * build. For Maven, Gradle and sbt, every matching version. Newest first.
     */
    Map<String, Object> json(Context ctx) throws Exception {
        Map<String, Object> doc = JsonOut.document();
        doc.put("tool", spec.tool().id());
        List<Object> available = new ArrayList<>();
        List<Installed> installed = ctx.store.list(spec.tool());
        if (spec.tool() == Tool.JDK || spec.tool() == Tool.GRAALVM) {
            Vendor vendor = Resolve.knownVendor(ctx, spec, vendorText);
            doc.put("vendor", vendor.name());
            doc.put("vendorChosen", vendorText != null);
            FoojaySource foojay = ctx.foojay();
            Map<Integer, Major> info = new TreeMap<>(Comparator.reverseOrder());
            foojay.majors().forEach(m -> info.put(m.major(), m));
            List<Pkg> pkgs;
            if (spec.version() != null && vendorText != null) {
                pkgs = new ArrayList<>(foojay.builds(spec.major(), vendor, ctx.platform, fx, pre));
            } else {
                pkgs = new ArrayList<>(foojay.newestPerVendorAndMajor(ctx.platform, fx, pre));
                if (spec.tool() == Tool.GRAALVM) {
                    pkgs.removeIf(p -> !p.vendor().isGraalVmBased());
                }
                if (vendorText != null) {
                    pkgs.removeIf(p -> !p.vendor().equals(vendor)); // jdk zulu: that vendor's majors only
                }
                if (spec.version() != null) {
                    pkgs = newestMatchingPerVendor(ctx, foojay, pkgs);
                }
            }
            pkgs.removeIf(p -> spec.version() != null && !p.version().matches(spec.version()));
            if (spec.tool() == Tool.GRAALVM) {
                pkgs.removeIf(p -> !p.vendor().isGraalVmBased());
            }
            if (maintained) {
                pkgs.removeIf(p -> info.containsKey(p.major()) && !info.get(p.major()).maintained());
            }
            pkgs.sort(Comparator.comparing(Pkg::version).reversed()
                    .thenComparing(p -> p.vendor().name()));
            Map<String, Behind> behind = behind(ctx, foojay);
            List<Object> majors = new ArrayList<>();
            for (Major m : info.values()) {
                if (!maintained || m.maintained()) {
                    majors.add(JsonOut.fields("major", m.major(), "lts", m.isLts(), "maintained", m.maintained()));
                }
            }
            doc.put("majors", majors);
            Map<Pkg, Verdict> verdicts = verdicts(ctx, foojay, pkgs, spec.version() != null && vendorText != null);
            for (Pkg p : pkgs) {
                Major m = info.get(p.major());
                Verdict verdict = verdicts.getOrDefault(p, Verdict.UNKNOWN);
                available.add(JsonOut.fields(
                        "tool", spec.tool().id(),
                        "version", p.version().raw(),
                        "major", p.major(),
                        "vendor", p.vendor().name(),
                        "graalvm", p.vendor().isGraalVmBased(),
                        "fx", fx,
                        "lts", m != null && m.isLts(),
                        "maintained", m == null || m.maintained(),
                        "preRelease", p.version().isPreRelease(),
                        "installed", !mark(installed, p.version(), p.vendor(), fx).isEmpty(),
                        "downloadable", verdict == Verdict.UNKNOWN ? null : verdict != Verdict.NO_DOWNLOAD,
                        "verifiable", verdict == Verdict.UNKNOWN || verdict == Verdict.NO_DOWNLOAD ? null
                                : verdict == Verdict.OK,
                        "behind", behindJson(behindOf(behind, p))));
            }
        } else {
            doc.put("majors", List.of());
            List<Version> versions = switch (spec.tool()) {
                case MAVEN -> ctx.maven().versions(pre);
                case GRADLE -> ctx.gradle().versions(pre);
                case SBT -> ctx.sbt().versions(pre);
                case JDK, GRAALVM -> throw new IllegalStateException("JDKs are listed above");
            };
            for (Version v : versions) {
                if (v.matches(spec.version())) {
                    available.add(JsonOut.fields(
                            "tool", spec.tool().id(),
                            "version", v.raw(),
                            "major", v.part(0),
                            "preRelease", v.isPreRelease(),
                            "installed", !mark(installed, v, null, false).isEmpty()));
                }
            }
        }
        doc.put("available", available);
        return doc;
    }

    private void searchJdk(Context ctx) throws Exception {
        Vendor vendor = Resolve.knownVendor(ctx, spec, vendorText);
        FoojaySource foojay = ctx.foojay();
        List<Pkg> index = new ArrayList<>(foojay.newestPerVendorAndMajor(ctx.platform, fx, pre));
        if (spec.tool() == Tool.GRAALVM) {
            index.removeIf(p -> !p.vendor().isGraalVmBased());
        }
        List<Installed> installed = ctx.store.list(spec.tool());
        if (index.isEmpty()) {
            ctx.out.println("no " + (fx ? "JavaFX " : "") + spec.tool() + " builds for " + ctx.platform
                    + (pre ? "" : " (pre-releases: --pre)"));
            return;
        }
        Map<String, Behind> behind = behind(index);
        if (spec.version() == null) {
            majorTable(ctx, foojay, foojay.majors(), index, vendor, installed, behind);
        } else if (vendorText == null) {
            vendorRows(ctx, foojay, newestMatchingPerVendor(ctx, foojay, index), vendor, installed, behind);
        } else {
            List<Pkg> builds = foojay.builds(spec.major(), vendor, ctx.platform, fx, pre).stream()
                    .filter(p -> p.version().matches(spec.version())).toList();
            if (builds.isEmpty()) {
                noMatch(ctx, vendor.name(), onlyPreReleases(() -> foojay.builds(spec.major(), vendor, ctx.platform, fx, true)
                        .stream().map(Pkg::version).toList())
                        ? preHint()
                        : "jkit search " + spec.tool().id() + "@" + spec.major() + " " + vendor.name()
                                + " lists its " + spec.major() + " builds");
                return;
            }
            Map<Pkg, Verdict> verdicts = verdicts(ctx, foojay, builds, true);
            Set<Verdict> shown = EnumSet.noneOf(Verdict.class);
            boolean anyBehind = false;
            for (Pkg p : builds) {
                String installedMark = mark(installed, p.version(), p.vendor(), fx);
                Verdict v = verdicts.getOrDefault(p, Verdict.UNKNOWN);
                String note = !installedMark.isEmpty() ? "" : v.note;
                if (!note.isEmpty()) {
                    shown.add(v);
                }
                String late = behindNote(behindOf(behind, p));
                anyBehind |= !late.isEmpty();
                ctx.out.println(p.version() + installedMark + note + late);
            }
            legendForRefused(ctx, shown);
            legendForBehind(ctx, anyBehind);
        }
    }

    /**
     * The builds of {@code index} that are {@link FoojaySource#behind}: a vendor's newest build of
     * a major, several updates older than another vendor's. Keyed by vendor, major and version, so
     * only that very build is marked, not the vendor's older ones.
     */
    private Map<String, Behind> behind(List<Pkg> index) {
        Map<String, Behind> out = new HashMap<>();
        FoojaySource.behind(index).forEach((p, b) -> out.put(p.vendor().name() + "|" + p.major() + "|" + p.version().raw(), b));
        return out;
    }

    /** For the JSON: the same index text search shows, which the lookups above have cached. */
    private Map<String, Behind> behind(Context ctx, FoojaySource foojay) {
        try {
            List<Pkg> index = new ArrayList<>(foojay.newestPerVendorAndMajor(ctx.platform, fx, pre));
            if (spec.tool() == Tool.GRAALVM) {
                index.removeIf(p -> !p.vendor().isGraalVmBased());
            }
            return behind(index);
        } catch (IOException | JkitException e) {
            return Map.of(); // offline and not cached: nothing to compare with
        }
    }

    /** Whether {@code p} is its vendor's newest build of its major and that one is behind. */
    private static Behind behindOf(Map<String, Behind> behind, Pkg p) {
        return behind.get(p.vendor().name() + "|" + p.major() + "|" + p.version().raw());
    }

    private static Object behindJson(Behind b) {
        return b == null ? null : JsonOut.fields("newest", b.newest().raw(), "vendors", b.vendors());
    }

    private static String behindNote(Behind b) {
        return b == null ? "" : "  " + BEHIND + " behind: newest " + b.newest().part(0) + " is " + b.newest();
    }

    private static void legendForBehind(Context ctx, boolean shown) {
        if (shown) {
            ctx.out.println(BEHIND + " behind: the vendor stopped updating this release; other vendors have newer builds");
        }
    }

    /** Versions that a search with --pre would also see. */
    @FunctionalInterface
    interface Versions {
        List<Version> get() throws Exception;
    }

    /** Whether a pre-release matches the version asked for, when no release does: then --pre is the answer. */
    private boolean onlyPreReleases(Versions withPre) {
        if (pre || spec.version() == null) {
            return false;
        }
        try {
            return withPre.get().stream().anyMatch(v -> v.isPreRelease() && v.matches(spec.version()));
        } catch (Exception e) {
            return false; // the plain hint then
        }
    }

    private String preHint() {
        return "only pre-releases match " + spec + ": add --pre";
    }

    /** What install would do with a build, as far as search can tell before downloading it. */
    enum Verdict {
        /** Installs: a checksum, or a publisher signature jkit checks. */
        OK(""),
        /** Refused without --allow-unverified: no checksum and no signature jkit can check. */
        UNVERIFIED("  ○ no checksum"),
        /** Refused outright: no build that matches has a direct download. */
        NO_DOWNLOAD("  – no direct download"),
        /** Not looked up (offline and not cached, or the lookup failed): shown as available. */
        UNKNOWN("");

        final String note;

        Verdict(String note) {
            this.note = note;
        }
    }

    /** How many builds search looks up at once: each is a small request, cached for 24 h. */
    static final int LOOKUP_THREADS = 8;

    /**
     * What install would do with each of {@code pkgs}: one cached lookup each, {@link
     * #LOOKUP_THREADS} at a time. {@code exact}: that very build (a vendor's build list); otherwise
     * what install picks for the version typed (the newest matching build with a direct download).
     */
    Map<Pkg, Verdict> verdicts(Context ctx, FoojaySource foojay, List<Pkg> pkgs, boolean exact) {
        Map<Pkg, Verdict> out = new ConcurrentHashMap<>();
        if (pkgs.isEmpty()) {
            return out;
        }
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<String> why = new AtomicReference<>();
        boolean offline = ctx.http().offline();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(LOOKUP_THREADS, pkgs.size()), r -> {
                    Thread t = new Thread(r, "jkit-search-lookup");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Pkg p : pkgs) {
                futures.add(pool.submit(() -> {
                    try {
                        FoojaySource.Availability a = exact ? foojay.availability(p)
                                : foojay.availability(p, spec.version(), ctx.platform, fx, pre);
                        out.put(p, verdict(a));
                    } catch (IOException | RuntimeException e) {
                        // offline and not cached, or the lookup failed: the build is listed as before
                        if (!offline) {
                            failed.incrementAndGet();
                            why.compareAndSet(null, String.valueOf(e.getMessage()));
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // each task catches its own failures
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (failed.get() > 0) {
            ctx.err.println("warning: couldn't check " + failed.get() + " of " + pkgs.size()
                    + " builds for a checksum (" + Visible.of(why.get())
                    + "); they're listed as available");
        }
        return out;
    }

    Verdict verdict(FoojaySource.Availability a) {
        if (!a.downloadable()) {
            return Verdict.NO_DOWNLOAD;
        }
        boolean signed = a.signature() != null && !a.retiredKey()
                && Signatures.owner(spec.tool(), a.pkg().vendor()) != null;
        return a.checksum() || signed ? Verdict.OK : Verdict.UNVERIFIED;
    }

    private void legendForRefused(Context ctx, Set<Verdict> shown) {
        if (shown.contains(Verdict.UNVERIFIED)) {
            ctx.out.println("○ no published checksum: install refuses it unless --allow-unverified");
        }
        if (shown.contains(Verdict.NO_DOWNLOAD)) {
            ctx.out.println("– no direct download: get it from the vendor's site");
        }
    }

    private static final String AVAILABLE = "✓";
    private static final String UNVERIFIED = "○";
    private static final String BEHIND = "↓";
    /** The most vendors search asks for a version's builds, one request each. */
    static final int MAX_VENDOR_LOOKUPS = 64;
    private static final String GAP = "  ";

    /** One column per major (maintained first, then end of life), one row per vendor. */
    private void majorTable(Context ctx, FoojaySource foojay, List<Major> majors, List<Pkg> index, Vendor vendor,
                            List<Installed> installed, Map<String, Behind> behind) {
        Map<Integer, Major> info = new TreeMap<>();
        majors.forEach(m -> info.put(m.major(), m));
        TreeSet<Integer> all = new TreeSet<>(Comparator.reverseOrder());
        Map<String, Set<Integer>> byVendor = new TreeMap<>();
        Map<String, Pkg> newest = new LinkedHashMap<>(); // per vendor and major
        for (Pkg p : index) {
            all.add(p.major());
            byVendor.computeIfAbsent(p.vendor().name(), k -> new HashSet<>()).add(p.major());
            newest.merge(p.vendor().name() + "|" + p.major(), p,
                    (a, b) -> a.version().compareTo(b.version()) >= 0 ? a : b);
        }
        List<Integer> current = new ArrayList<>();
        List<Integer> ended = new ArrayList<>();
        for (int major : all) {
            Major m = info.get(major);
            (m != null && !m.maintained() ? ended : current).add(major);
        }
        if (maintained) {
            ended.clear();
            byVendor.values().removeIf(set -> set.stream().noneMatch(current::contains));
        }
        List<Pkg> shownPkgs = newest.values().stream()
                .filter(p -> byVendor.containsKey(p.vendor().name()))
                .filter(p -> current.contains(p.major()) || ended.contains(p.major()))
                .toList();
        Map<Pkg, Verdict> found = verdicts(ctx, foojay, shownPkgs, false);
        Map<String, Verdict> verdicts = new HashMap<>();
        found.forEach((p, v) -> verdicts.put(p.vendor().name() + "|" + p.major(), v));
        Set<String> late = new HashSet<>();
        for (Pkg p : shownPkgs) {
            if (behindOf(behind, p) != null) {
                late.add(p.vendor().name() + "|" + p.major());
            }
        }

        String chosen = vendor.name();
        List<String> vendors = new ArrayList<>();
        if (byVendor.containsKey(chosen)) {
            vendors.add(chosen);
        }
        byVendor.keySet().stream().filter(v -> !v.equals(chosen)).forEach(vendors::add);
        int nameWidth = 0;
        for (String v : vendors) {
            nameWidth = Math.max(nameWidth, v.length() + 2);
        }

        StringBuilder groups = new StringBuilder(" ".repeat(nameWidth));
        StringBuilder numbers = new StringBuilder(" ".repeat(nameWidth));
        StringBuilder lts = new StringBuilder(" ".repeat(nameWidth));
        header(groups, numbers, lts, current, info, "maintained");
        if (!ended.isEmpty()) {
            groups.append(GAP);
            numbers.append(GAP);
            lts.append(GAP);
            header(groups, numbers, lts, ended, info, "end of life");
        }
        ctx.out.println(groups.toString().stripTrailing());
        ctx.out.println(numbers.toString().stripTrailing());
        ctx.out.println(lts.toString().stripTrailing());

        boolean anyBehind = false;
        for (String v : vendors) {
            Set<Integer> has = byVendor.get(v);
            String label = v.equals(chosen) ? v + " *" : v;
            StringBuilder line = new StringBuilder(String.format("%-" + nameWidth + "s", label));
            anyBehind |= cells(line, current, has, v, installed, fx, verdicts, late);
            if (!ended.isEmpty()) {
                line.append(GAP);
                anyBehind |= cells(line, ended, has, v, installed, fx, verdicts, late);
            }
            ctx.out.println(line.toString().stripTrailing());
        }
        ctx.out.println();
        boolean anyUnverified = verdicts.containsValue(Verdict.UNVERIFIED);
        ctx.out.println("✓ available   " + (anyUnverified ? "○ no checksum (needs --allow-unverified)   " : "")
                + (anyBehind ? BEHIND + " behind (the vendor stopped updating it)   " : "")
                + "● installed   ‾‾ LTS   * " + (vendorText == null ? "default vendor" : "chosen vendor"));
        ctx.out.println("versions per vendor: jkit search " + spec.tool().name().toLowerCase() + "@<major>");
    }

    private static void header(StringBuilder groups, StringBuilder numbers, StringBuilder lts, List<Integer> majors,
                               Map<Integer, Major> info, String title) {
        int start = numbers.length();
        for (int i = 0; i < majors.size(); i++) {
            String n = String.valueOf(majors.get(i));
            String sep = i == 0 ? "" : " ";
            numbers.append(sep).append(n);
            Major m = info.get(majors.get(i));
            lts.append(sep).append((m != null && m.isLts() ? "‾" : " ").repeat(n.length()));
        }
        int span = numbers.length() - start;
        if (span == 0) {
            return;
        }
        String t = title.length() <= span ? title : title.substring(0, span);
        groups.append(String.format("%-" + span + "s", t));
    }

    /** Returns whether a cell shows {@link #BEHIND}, for the legend. */
    private static boolean cells(StringBuilder line, List<Integer> majors, Set<Integer> has, String vendor,
                                 List<Installed> installed, boolean fx, Map<String, Verdict> verdicts, Set<String> late) {
        boolean behind = false;
        for (int i = 0; i < majors.size(); i++) {
            int major = majors.get(i);
            String n = String.valueOf(major);
            Verdict verdict = verdicts.getOrDefault(vendor + "|" + major, Verdict.UNKNOWN);
            String mark = !has.contains(major) ? " "
                    : isInstalled(installed, major, vendor, fx) ? "●"
                    : verdict == Verdict.NO_DOWNLOAD ? " "
                    : verdict == Verdict.UNVERIFIED ? UNVERIFIED
                    : late.contains(vendor + "|" + major) ? BEHIND : AVAILABLE;
            line.append(i == 0 ? "" : " ").append(" ".repeat(n.length() - 1)).append(mark);
            behind |= mark.equals(BEHIND);
        }
        return behind;
    }

    private static boolean isInstalled(List<Installed> installed, int major, String vendor, boolean fx) {
        for (Installed i : installed) {
            if (i.major() == major && i.fx() == fx && i.vendor() != null && i.vendor().name().equals(vendor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Says that nothing matches, in place of an empty table: "no zulu jdk build matches
     * jdk@21.0.99", then {@code hint} (what to search for instead), if any.
     */
    private void noMatch(Context ctx, String vendor, String hint) {
        String what = (vendor == null ? "" : vendor + " ") + (fx ? "JavaFX " : "") + spec.tool().id()
                + (spec.tool().hasVendors() ? " build" : " version");
        ctx.out.println("no " + what + (spec.version() == null ? " for " + ctx.platform : " matches " + spec));
        if (hint != null) {
            ctx.out.println(hint);
        }
    }

    private void vendorRows(Context ctx, FoojaySource foojay, List<Pkg> index, Vendor vendor,
                            List<Installed> installed, Map<String, Behind> behind) {
        TreeMap<String, Pkg> byVendor = new TreeMap<>();
        for (Pkg p : index) {
            byVendor.put(p.vendor().name(), p);
        }
        if (byVendor.isEmpty()) {
            noMatch(ctx, null, onlyPreReleases(() -> foojay.newestPerVendorAndMajor(ctx.platform, fx, true).stream()
                    .filter(p -> spec.tool() != Tool.GRAALVM || p.vendor().isGraalVmBased())
                    .map(Pkg::version).toList())
                    ? preHint() : "jkit search " + spec.tool().id() + " lists the versions there are");
            return;
        }
        Map<Pkg, Verdict> verdicts = verdicts(ctx, foojay, new ArrayList<>(byVendor.values()), false);
        Set<Verdict> shown = EnumSet.noneOf(Verdict.class);
        boolean anyBehind = false;
        for (Pkg p : byVendor.values()) {
            String name = p.vendor().equals(vendor) ? p.vendor().name() + " *" : p.vendor().name();
            String installedMark = mark(installed, p.version(), p.vendor(), fx);
            Verdict v = verdicts.getOrDefault(p, Verdict.UNKNOWN);
            String note = installedMark.isEmpty() ? v.note : "";
            if (!note.isEmpty()) {
                shown.add(v);
            }
            String late = behindNote(behindOf(behind, p));
            anyBehind |= !late.isEmpty();
            ctx.out.println(String.format("%-16s %s", name, p.version() + installedMark + note + late));
        }
        ctx.out.println("* default vendor   ● installed   · all builds of one vendor: jkit search "
                + spec + " <vendor>");
        legendForRefused(ctx, shown);
        legendForBehind(ctx, anyBehind);
    }

    private void grouped(Context ctx, List<Version> versions, Versions withPre) throws Exception {
        List<Installed> installed = ctx.store.list(spec.tool());
        Map<String, List<Version>> groups = new LinkedHashMap<>();
        for (Version v : versions) {
            if (v.matches(spec.version())) {
                groups.computeIfAbsent(v.part(0) + "." + v.part(1), k -> new ArrayList<>()).add(v);
            }
        }
        if (groups.isEmpty()) {
            noMatch(ctx, null, spec.version() == null ? null
                    : onlyPreReleases(withPre) ? preHint()
                    : "jkit search " + spec.tool().id() + " lists the versions there are");
            return;
        }
        for (Map.Entry<String, List<Version>> g : groups.entrySet()) {
            StringBuilder line = new StringBuilder(String.format("%-6s", g.getKey()));
            List<Version> list = g.getValue();
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                Version v = list.get(i);
                line.append(v).append(mark(installed, v, null, false)).append(v.isPreRelease() ? " (pre-release)" : "")
                        .append("  ");
            }
            if (list.size() > 8) {
                line.append("…");
            }
            ctx.out.println(line.toString().stripTrailing());
        }
    }

    /**
     * Each vendor's newest build of the spec's major that matches it. The index holds only the newest
     * build per vendor and major, so for {@code jdk@21.0.5} a vendor whose newest is 21.0.9 is looked
     * up build by build.
     */
    private List<Pkg> newestMatchingPerVendor(Context ctx, FoojaySource foojay, List<Pkg> index) throws Exception {
        List<Pkg> out = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Exception last = null;
        int lookups = 0;
        for (Pkg p : index) {
            if (p.major() != spec.major()) {
                continue;
            }
            if (p.version().matches(spec.version())) {
                out.add(p);
                continue;
            }
            if (++lookups > MAX_VENDOR_LOOKUPS) {
                // one request per vendor: real lists name about 30, a hostile one could name thousands
                failed.add(p.vendor().name());
                last = new IOException("more than " + MAX_VENDOR_LOOKUPS + " vendors to ask");
                continue;
            }
            try {
                foojay.builds(spec.major(), p.vendor(), ctx.platform, fx, pre).stream()
                        .filter(b -> b.version().matches(spec.version()))
                        .max(Comparator.comparing(Pkg::version))
                        .ifPresent(out::add);
            } catch (IOException | JkitException e) {
                // one vendor's list failing mustn't hide every other vendor's builds
                failed.add(p.vendor().name());
                last = e;
            }
        }
        if (!failed.isEmpty()) {
            if (out.isEmpty()) {
                throw last;
            }
            String names = failed.size() <= 5 ? String.join(", ", failed)
                    : String.join(", ", failed.subList(0, 5)) + " and " + (failed.size() - 5) + " more";
            ctx.err.println("warning: couldn't list " + names + " builds (" + last.getMessage()
                    + "), so they're left out");
        }
        return out;
    }

    private static String mark(List<Installed> installed, Version v, Vendor vendor, boolean fx) {
        for (Installed i : installed) {
            if (i.version().equals(v) && i.fx() == fx && (vendor == null || vendor.equals(i.vendor()))) {
                return INSTALLED;
            }
        }
        return "";
    }
}
