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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Parameters(index = "0", paramLabel = "<tool>[@version]", converter = ToolSpecConverter.class,
            description = "jdk, maven, gradle or graalvm, optionally with a version prefix.")
    ToolSpec spec;

    @Parameters(index = "1", arity = "0..1", paramLabel = "[vendor]", description = "JDK vendor, e.g. temurin.")
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
        Vendor vendor = Resolve.vendor(ctx, spec, vendorText);
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

    private void majorTable(Context ctx, List<Major> majors, List<Pkg> index, Vendor vendor,
                            List<Installed> installed) {
        Map<Integer, Major> info = new TreeMap<>();
        majors.forEach(m -> info.put(m.major(), m));
        TreeMap<Integer, List<Pkg>> byMajor = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Pkg p : index) {
            byMajor.computeIfAbsent(p.major(), k -> new ArrayList<>()).add(p);
        }
        String head = "%-5s %-4s %-15s ";
        ctx.out.println(String.format(head, "major", "", vendor + " *") + "also from");
        int prefix = String.format(head, "", "", "").length();
        for (Map.Entry<Integer, List<Pkg>> e : byMajor.entrySet()) {
            Major m = info.get(e.getKey());
            boolean eol = m != null && !m.maintained();
            if (maintained && eol) {
                continue;
            }
            String tag = m != null && m.isLts() ? "LTS" : eol ? "eol" : "";
            String own = "–";
            TreeSet<String> others = new TreeSet<>();
            for (Pkg p : e.getValue()) {
                if (p.vendor().equals(vendor)) {
                    own = p.version() + mark(installed, p.version(), p.vendor());
                } else {
                    others.add(p.vendor().name());
                }
            }
            ctx.out.println(String.format(head, e.getKey(), tag, own) + fit(others, ctx.width() - prefix));
        }
        ctx.out.println("* " + (vendorText == null ? "default vendor" : "chosen vendor")
                + "   ● installed   eol = no longer maintained");
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

    static String fit(TreeSet<String> names, int width) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String n : names) {
            int remaining = names.size() - shown - 1;
            String suffix = remaining > 0 ? " +" + remaining : "";
            if (sb.length() + n.length() + 1 + suffix.length() > width && shown > 0) {
                return sb.append(" +").append(names.size() - shown).toString();
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(n);
            shown++;
        }
        return sb.toString();
    }
}
