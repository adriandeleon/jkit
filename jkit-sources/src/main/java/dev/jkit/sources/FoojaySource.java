package dev.jkit.sources;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import dev.jkit.model.Suggest;

/** JDK builds from the foojay Disco API (https://api.foojay.io/disco/v3.0). */
public final class FoojaySource {
    public static final URI DEFAULT_BASE = URI.create("https://api.foojay.io/disco/v3.0/");

    /** One JDK package for one platform. */
    public record Pkg(String id, Vendor vendor, int major, Version version, String fileName,
                      String archiveType, String termOfSupport) { }

    /** A feature release and its support status. */
    public record Major(int major, String termOfSupport, boolean maintained) {
        public boolean isLts() { return termOfSupport.equalsIgnoreCase("LTS"); }
    }

    private final Http http;
    private final URI base;

    public FoojaySource(Http http, URI base) {
        this.http = http;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    }

    /** Newest build per vendor and major version for one platform: one request. */
    public List<Pkg> newestPerVendorAndMajor(Platform platform, boolean fx) throws IOException {
        return newestPerVendorAndMajor(platform, fx, false);
    }

    /** As above; with {@code pre}, early-access builds count too. */
    public List<Pkg> newestPerVendorAndMajor(Platform platform, boolean fx, boolean pre) throws IOException {
        return packages(platform, "latest=available&release_status=ga" + (pre ? "&release_status=ea" : ""), fx);
    }

    /** Every build of one major from one vendor, newest first. */
    public List<Pkg> builds(int major, Vendor vendor, Platform platform, boolean fx, boolean pre)
            throws IOException {
        String status = pre ? "release_status=ga&release_status=ea" : "release_status=ga";
        List<Pkg> all = packages(platform,
                "jdk_version=" + major + "&distribution="
                        + java.net.URLEncoder.encode(vendor.foojayName(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&" + status, fx);
        all.sort(Comparator.comparing(Pkg::version).reversed());
        return all;
    }

    /**
     * Checks a vendor against foojay's distribution list (names and their synonyms, so "SAP
     * Machine" or "OpenJDK" work too) and returns it under foojay's name. An unknown name fails
     * with the closest match, before any version request can come back as a bare HTTP 400.
     *
     * @param source how the name was given, for the message: "" when typed, else e.g. "your
     *               default vendor (jdk.vendor)"
     */
    public Vendor requireKnown(Vendor vendor, String typed, String source) throws IOException {
        JsonNode root = Json.fetch(http, base.resolve(
                "distributions?include_versions=false&include_synonyms=true"));
        Map<String, String> bySynonym = new java.util.HashMap<>();
        TreeSet<String> names = new TreeSet<>();
        for (JsonNode d : root.path("result")) {
            String param = Json.text(d, "api_parameter");
            if (!Vendor.isSafeName(param)) {
                continue;
            }
            Vendor known = Vendor.fromFoojay(param);
            if (known.isRetired()) {
                continue;
            }
            names.add(known.name());
            bySynonym.put(param, param);
            bySynonym.put(known.name(), param);
            for (JsonNode syn : d.path("synonyms")) {
                bySynonym.put(syn.asText().toLowerCase(Locale.ROOT), param);
            }
        }
        String match = bySynonym.get(vendor.foojayName());
        if (match == null) {
            match = bySynonym.get(typed.toLowerCase(Locale.ROOT).trim());
        }
        if (match != null) {
            return Vendor.fromFoojay(match);
        }
        String guess = Suggest.closest(typed, names);
        String what = source.isEmpty() ? "unknown JDK vendor '" + typed + "'"
                : source + " '" + typed + "' is not a known JDK vendor";
        List<String> hints = new ArrayList<>();
        if (Version.looksLikeVersion(typed)) {
            hints.add("a version goes after @, not a space: jdk@" + typed.trim());
        } else if (guess != null) {
            hints.add("did you mean: " + guess + "?");
        }
        hints.add("vendors: " + String.join(", ", names));
        return throwUnknown(what, hints);
    }

    private static Vendor throwUnknown(String message, List<String> hints) {
        throw new JkitException(2, message, hints.toArray(String[]::new)); // a bad argument
    }

    public List<Major> majors() throws IOException {
        JsonNode root = Json.fetch(http, base.resolve(
                "major_versions?ea=false&ga=true&maintained=false&include_build=false&include_versions=false"));
        List<Major> out = new ArrayList<>();
        for (JsonNode n : root.path("result")) {
            out.add(new Major(n.path("major_version").asInt(), Json.text(n, "term_of_support"),
                    n.path("maintained").asBoolean()));
        }
        out.sort(Comparator.comparingInt(Major::major).reversed());
        return out;
    }

    /**
     * Resolves {@code versionSpec} (null = newest) for one vendor and platform to a downloadable
     * artifact with its checksum.
     */
    public Artifact resolve(Tool tool, String versionSpec, Vendor vendor, Platform platform,
                            boolean fx, boolean pre) throws IOException {
        int major;
        if (versionSpec == null) {
            major = newestPerVendorAndMajor(platform, fx).stream()
                    .filter(p -> p.vendor().equals(vendor))
                    .mapToInt(Pkg::major).max()
                    .orElseThrow(() -> new JkitException(vendor + " has no builds for " + platform));
        } else {
            major = Version.parse(versionSpec).part(0);
        }
        List<Pkg> candidates = builds(major, vendor, platform, fx, pre).stream()
                .filter(p -> p.version().matches(versionSpec))
                .filter(p -> pre || !p.version().isPreRelease())
                .toList();
        if (candidates.isEmpty()) {
            throw noMatch(tool, versionSpec, vendor, platform, fx, major);
        }
        // the newest build that can be downloaded directly; some vendors list builds behind a form
        for (Pkg pkg : candidates) {
            // this answer carries the download link and often the checksum itself, so https too
            JsonNode info = Json.fetch(http, Http.requireSecure(base.resolve("ids/" + pkg.id())))
                    .path("result").path(0);
            String url = Json.text(info, "direct_download_uri");
            if (url.isEmpty()) {
                continue;
            }
            Checksum checksum = Checksum.of(Json.text(info, "checksum"), Json.text(info, "checksum_type"));
            String checksumUri = Json.text(info, "checksum_uri");
            if (checksum == null && !checksumUri.isEmpty() && !http.offline()) {
                URI uri = Http.sent(checksumUri, "a checksum");
                checksum = Checksum.parse(http.getFresh(uri), pkg.fileName(), Json.text(info, "checksum_type"));
            }
            return new Artifact(tool, pkg.version(), pkg.vendor(), Http.sent(url, "a download"), pkg.fileName(),
                    pkg.archiveType(), checksum == null ? null : checksum.algorithm(),
                    checksum == null ? null : checksum.hash(), fx);
        }
        Pkg newest = candidates.getFirst();
        throw new JkitException(newest.vendor() + " " + newest.version() + " has no direct download",
                "download it from the vendor's site, or pick another vendor");
    }

    private JkitException noMatch(Tool tool, String spec, Vendor vendor, Platform platform,
                                  boolean fx, int major) {
        String what = tool.id() + (spec == null ? "" : "@" + spec);
        try {
            TreeSet<String> others = new TreeSet<>();
            for (Pkg p : newestPerVendorAndMajor(platform, fx)) {
                if (p.major() == major && !p.vendor().equals(vendor)
                        && (tool != Tool.GRAALVM || p.vendor().isGraalVmBased())) {
                    others.add(p.vendor().name());
                }
            }
            if (!others.isEmpty()) {
                return new JkitException(vendor + " has no build matching " + what + " for " + platform,
                        "available from: " + String.join(", ", others));
            }
        } catch (IOException ignored) {
            // fall through to the plain message
        }
        return new JkitException(vendor + " has no build matching " + what + " for " + platform);
    }

    private List<Pkg> packages(Platform platform, String extra, boolean fx) throws IOException {
        String query = "packages?operating_system=" + platform.os()
                + "&architecture=" + platform.arch()
                + "&archive_type=tar.gz&archive_type=zip&package_type=jdk"
                + "&javafx_bundled=" + fx + "&" + extra;
        JsonNode root = Json.fetch(http, base.resolve(query));
        String preferred = platform.isWindows() ? "zip" : "tar.gz";
        // one package per vendor + version, preferring the platform's usual archive type
        Map<String, Pkg> byKey = new LinkedHashMap<>();
        for (JsonNode n : root.path("result")) {
            if (platform.isLinux() && !Json.text(n, "lib_c_type").equals(platform.libc())) {
                continue;
            }
            if (n.path("feature").size() > 0) {
                continue; // CRaC, Leyden and other variants are excluded by default
            }
            if (n.path("javafx_bundled").asBoolean() != fx) {
                continue;
            }
            if (!Version.isSafeText(Json.text(n, "java_version")) || !Vendor.isSafeName(Json.text(n, "distribution"))
                    || !Json.text(n, "id").matches("[A-Za-z0-9_-]{1,128}")
                    || !Json.text(n, "filename").matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}")
                    || !java.util.Set.of("tar.gz", "tgz", "zip").contains(Json.text(n, "archive_type"))) {
                continue; // not text jkit can use as a folder name
            }
            Pkg pkg = new Pkg(Json.text(n, "id"), Vendor.fromFoojay(Json.text(n, "distribution")),
                    n.path("major_version").asInt(), Version.parse(Json.text(n, "java_version")),
                    Json.text(n, "filename"), Json.text(n, "archive_type"), Json.text(n, "term_of_support"));
            if (pkg.vendor().isRetired()) {
                continue;
            }
            String key = pkg.vendor().foojayName() + "|" + pkg.version().raw();
            Pkg existing = byKey.get(key);
            if (existing == null || (!existing.archiveType().equals(preferred) && pkg.archiveType().equals(preferred))) {
                byKey.put(key, pkg);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
