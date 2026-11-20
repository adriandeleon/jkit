package dev.jkit.cli;

import dev.jkit.core.Installed;
import dev.jkit.model.Tool;
import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.FoojaySource;
import dev.jkit.sources.FoojaySource.Major;
import dev.jkit.sources.FoojaySource.Pkg;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "search", description = "Lists available versions.")
final class SearchCommand implements Callable<Integer> {
    private static final String INSTALLED = " ●";

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class, completionCandidates = Candidates.Tools.class,
            description = "jdk, maven, gradle or graalvm, optionally with a version prefix.")
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]", completionCandidates = Candidates.Vendors.class, description = "JDK vendor, e.g. temurin.")
    String vendorText;

    @Option(names = "--fx", description = "JavaFX builds.")
    boolean fx;

    @Option(names = "--pre", description = "Include pre-releases.")
    boolean pre;

    @Option(names = "--maintained", description = "Hide JDK versions that are no longer maintained.")
    boolean maintained;

    @Mixin
    Options options;

    @Override
    public Integer call() throws Exception {
        Context ctx = options.context();
        switch (spec.tool()) {
            case JDK, GRAALVM -> searchJdk(ctx);
            case MAVEN -> grouped(ctx, ctx.maven().versions(pre));
            case GRADLE -> grouped(ctx, ctx.gradle().versions(pre));
        }
        return 0;
    }

    private void searchJdk(Context ctx) throws Exception {
        Vendor vendor = Resolve.knownVendor(ctx, spec, vendorText);
        FoojaySource foojay = ctx.foojay();
        List<Pkg> index = new ArrayList<>(foojay.newestPerVendorAndMajor(ctx.platform, fx));
        if (spec.tool() == Tool.GRAALVM) {
            index.removeIf(p -> !p.vendor().isGraalVmBased());
        }
        List<Installed> installed = ctx.store.list(spec.tool());
        if (spec.version() == null) {
            majorTable(ctx, foojay.majors(), index, vendor, installed);
        } else if (vendorText == null) {
            vendorRows(ctx, index, vendor, installed);
        } else {
            for (Pkg p : foojay.builds(spec.major(), vendor, ctx.platform, fx, pre)) {
                if (p.version().matches(spec.version())) {
                    ctx.out.println(p.version() + mark(installed, p.version(), p.vendor()));
                }
            }
        }
    }

    private static final String AVAILABLE = "✓";
    private static final String GAP = "  ";

    /** One column per major (maintained first, then end of life), one row per vendor. */
    private void majorTable(Context ctx, List<Major> majors, List<Pkg> index, Vendor vendor,
                            List<Installed> installed) {
        Map<Integer, Major> info = new TreeMap<>();
        majors.forEach(m -> info.put(m.major(), m));
        TreeSet<Integer> all = new TreeSet<>(java.util.Comparator.reverseOrder());
        Map<String, Set<Integer>> byVendor = new TreeMap<>();
        for (Pkg p : index) {
            all.add(p.major());
            byVendor.computeIfAbsent(p.vendor().name(), k -> new HashSet<>()).add(p.major());
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

        for (String v : vendors) {
            Set<Integer> has = byVendor.get(v);
            String label = v.equals(chosen) ? v + " *" : v;
            StringBuilder line = new StringBuilder(String.format("%-" + nameWidth + "s", label));
            cells(line, current, has, v, installed);
            if (!ended.isEmpty()) {
                line.append(GAP);
                cells(line, ended, has, v, installed);
            }
            ctx.out.println(line.toString().stripTrailing());
        }
        ctx.out.println();
        ctx.out.println("✓ available   ● installed   ‾‾ LTS   * " + (vendorText == null ? "default vendor" : "chosen vendor"));
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
        String t = title.length() <= span ? title : title.substring(0, span);
        groups.append(String.format("%-" + span + "s", t));
    }

    private static void cells(StringBuilder line, List<Integer> majors, Set<Integer> has, String vendor,
                              List<Installed> installed) {
        for (int i = 0; i < majors.size(); i++) {
            int major = majors.get(i);
            String n = String.valueOf(major);
            String mark = !has.contains(major) ? " " : isInstalled(installed, major, vendor) ? "●" : AVAILABLE;
            line.append(i == 0 ? "" : " ").append(" ".repeat(n.length() - 1)).append(mark);
        }
    }

    private static boolean isInstalled(List<Installed> installed, int major, String vendor) {
        for (Installed i : installed) {
            Version v = i.version();
            int m = v.part(0) == 1 ? v.part(1) : v.part(0);
            if (m == major && i.vendor() != null && i.vendor().name().equals(vendor)) {
                return true;
            }
        }
        return false;
    }

    private void vendorRows(Context ctx, List<Pkg> index, Vendor vendor, List<Installed> installed) {
        int major = spec.major();
        TreeMap<String, Pkg> byVendor = new TreeMap<>();
        for (Pkg p : index) {
            if (p.major() == major && p.version().matches(spec.version())) {
                byVendor.put(p.vendor().name(), p);
            }
        }
        for (Pkg p : byVendor.values()) {
            String name = p.vendor().equals(vendor) ? p.vendor().name() + " *" : p.vendor().name();
            ctx.out.println(String.format("%-16s %s", name, p.version() + mark(installed, p.version(), p.vendor())));
        }
        ctx.out.println("* default vendor   ● installed   · all builds of one vendor: jkit search "
                + spec + " <vendor>");
    }

    private void grouped(Context ctx, List<Version> versions) throws Exception {
        List<Installed> installed = ctx.store.list(spec.tool());
        Map<String, List<Version>> groups = new LinkedHashMap<>();
        for (Version v : versions) {
            if (v.matches(spec.version())) {
                groups.computeIfAbsent(v.part(0) + "." + v.part(1), k -> new ArrayList<>()).add(v);
            }
        }
        for (Map.Entry<String, List<Version>> g : groups.entrySet()) {
            StringBuilder line = new StringBuilder(String.format("%-6s", g.getKey()));
            List<Version> list = g.getValue();
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                Version v = list.get(i);
                line.append(v).append(mark(installed, v, null)).append(v.isPreRelease() ? " (pre-release)" : "")
                        .append("  ");
            }
            if (list.size() > 8) {
                line.append("…");
            }
            ctx.out.println(line.toString().stripTrailing());
        }
    }

    private static String mark(List<Installed> installed, Version v, Vendor vendor) {
        for (Installed i : installed) {
            if (i.version().equals(v) && (vendor == null || vendor.equals(i.vendor()))) {
                return INSTALLED;
            }
        }
        return "";
    }
}
