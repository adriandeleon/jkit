package dev.jkit.sources;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jkit.model.JkitException;
import dev.jkit.model.Platform;
import dev.jkit.model.Suggest;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.model.Visible;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** JDK builds from the foojay Disco API (https://api.foojay.io/disco/v3.0). */
public final class FoojaySource {
    /**
     * How many builds an install looks up for a direct download before giving up. Oracle lists its
     * newer JDK 17 updates without one (they need a licence click-through), and that list grows by
     * a build each quarter: 32 covers years of that, while still bounding what a hostile list costs.
     */
    static final int MAX_LOOKUPS = 32;

    public static final URI DEFAULT_BASE = URI.create("https://api.foojay.io/disco/v3.0/");

    /** Amazon's old CloudFront host, which still serves its 2019 Corretto builds, and only those. */
    static final String CORRETTO_2019_HOST = "d3pxv6yz143wms.cloudfront.net";

    /**
     * One JDK package for one platform.
     *
     * @param directlyDownloadable what foojay's list says; false for builds behind a licence form
     */
    public record Pkg(String id, Vendor vendor, int major, Version version, String fileName,
                      String archiveType, String termOfSupport, boolean directlyDownloadable) { }

    /**
     * What an install of a build would find, so search can say beforehand what install refuses.
     *
     * @param pkg       the build install would download, or null when none has a direct download
     * @param checksum  whether a checksum is published for it (in foojay's answer or as a file)
     * @param signature its publisher's signature link, or null
     */
    public record Availability(Pkg pkg, boolean checksum, URI signature) {
        static final Availability NONE = new Availability(null, false, null);

        public boolean downloadable() {
            return pkg != null;
        }

        /**
         * Whether its signature is by a key the vendor has retired, which install can't check:
         * Amazon's 2019 Corretto builds, the ones it still serves from its old CloudFront host
         * (all of them, and nothing else, are there).
         */
        public boolean retiredKey() {
            return pkg != null && signature != null && pkg.vendor().foojayName().equals("corretto")
                    && CORRETTO_2019_HOST.equalsIgnoreCase(signature.getHost());
        }
    }

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

    /**
     * Every build of one major from one vendor, newest first. Only those: the vendor a build is
     * listed under decides which key its signature must be by, or whether jkit looks for one, and
     * its version whether Temurin signed it. A list that answers a question about Temurin 21 with a
     * build it calls Zulu, or Temurin 17.0.3, would otherwise choose to skip the signature check.
     */
    public List<Pkg> builds(int major, Vendor vendor, Platform platform, boolean fx, boolean pre)
            throws IOException {
        String status = pre ? "release_status=ga&release_status=ea" : "release_status=ga";
        List<Pkg> all = packages(platform,
                "jdk_version=" + major + "&distribution="
                        + URLEncoder.encode(vendor.foojayName(), StandardCharsets.UTF_8)
                        + "&" + status, fx);
        // vendor names come back as foojay's own (requireKnown turns synonyms into those)
        all.removeIf(p -> !p.vendor().foojayName().equals(vendor.foojayName())
                || p.major() != major || p.version().part(0) != major);
        all.sort(Comparator.comparing(Pkg::version).reversed());
        return all;
    }

    /**
     * The package's signature link. Temurin, Microsoft and Corretto, the vendors jkit has keys for,
     * publish a {@code .sig} next to each download they sign (Temurin's GitHub releases, aka.ms,
     * corretto.aws): for those, jkit takes that link whether or not foojay lists one. foojay leaves
     * it out for some Temurin builds that have one, and a compromised foojay could leave it out on
     * purpose, or point the download somewhere else, to have a build installed on its own checksum:
     * the link is next to whatever download foojay names, and a signature from there must still be
     * by the vendor's key. A download link with no file name to put .sig after is refused: without
     * a signature such a build would install on foojay's checksum.
     * <p>
     * For other builds, the link foojay lists, https only: a listed link that isn't https is
     * refused for a vendor jkit has keys for, and dropped for others, whose links jkit never fetches.
     */
    static URI signature(JsonNode info, URI download, URI base, Pkg pkg) {
        if (vendorSigns(pkg.vendor(), pkg.version())) {
            URI next = nextTo(download);
            if (next == null) {
                throw new JkitException(base.getHost() + " lists the download of " + pkg.vendor() + " " + pkg.version()
                        + " as " + Visible.of(download.toString()) + ", where jkit can't find its signature; refusing it",
                        "try again later, or pick another vendor");
            }
            return next;
        }
        String sig = Json.text(info, "signature_uri");
        if (sig.isEmpty()) {
            return null;
        }
        try {
            return Http.sent(sig, "a signature", base);
        } catch (RuntimeException e) {
            if (SIGNING_VENDORS.contains(pkg.vendor().foojayName())) {
                throw e;
            }
            return null;
        }
    }

    /** The JDK vendors jkit has signing keys for (Signatures in jkit-core). */
    static final Set<String> SIGNING_VENDORS = Set.of("temurin", "microsoft", "corretto");

    /**
     * The first Temurin release of each feature version with a {@code .sig} on GitHub: Adoptium
     * started signing in July 2022 (8u342, 11.0.16, 17.0.4, 18.0.2), and signs every build since,
     * early-access ones included. Older ones, and 16, have none.
     */
    private static final Map<Integer, Version> TEMURIN_FIRST_SIGNED = Map.of(
            8, Version.parse("8.0.342"), 11, Version.parse("11.0.16"),
            17, Version.parse("17.0.4"), 18, Version.parse("18.0.2"));

    /**
     * Whether jkit looks for the vendor's signature of this build: every Microsoft and Corretto
     * build, and Temurin's from mid-2022 on. Not that each has one jkit can check: Amazon signed
     * its 2019 Corretto builds with keys it has since retired, and a few old Corretto downloads
     * (Alpine builds of 2020, a macOS 8u242) have no .sig at all. Those are refused unless
     * --allow-unverified, as any build whose signature can't be checked.
     */
    static boolean vendorSigns(Vendor vendor, Version version) {
        return switch (vendor.foojayName()) {
            case "microsoft", "corretto" -> true;
            case "temurin" -> {
                Version first = TEMURIN_FIRST_SIGNED.get(version.part(0));
                yield first != null ? version.compareTo(first) >= 0 : version.part(0) >= 19;
            }
            default -> false;
        };
    }

    /**
     * Whether {@code download} is where {@code vendor} publishes its builds, for the vendors jkit
     * has keys for, in the exact shape of the vendor's links, ending in the build's own file name:
     * a release asset of one of Adoptium's temurin&lt;N&gt;-binaries repositories, aka.ms/download-jdk/,
     * and corretto.aws/downloads/resources/&lt;version&gt;/ (or &lt;version&gt;/ on Amazon's older
     * CloudFront host, which still serves its 2019 builds). That is every build foojay lists for
     * them. The host alone isn't enough: github.com serves any commit of a fork from the original
     * repository's path (/adoptium/&lt;repo&gt;/archive/&lt;sha&gt;.tar.gz), whoever pushed it.
     * <p>
     * Only the address foojay names is checked: from there the vendor's own server redirects
     * (aka.ms to download.visualstudio.microsoft.com, GitHub to its release-assets host), which
     * foojay doesn't decide, and Http checks each hop stays https.
     */
    static boolean onVendorHost(String vendor, URI download, String fileName) {
        String host = download.getHost() == null ? "" : download.getHost().toLowerCase(Locale.ROOT);
        String path = download.getRawPath() == null ? "" : download.getRawPath();
        boolean plain = download.getPort() == -1 && download.getRawUserInfo() == null && download.getRawQuery() == null
                && download.getRawFragment() == null && !path.contains("..")
                && !path.toLowerCase(Locale.ROOT).contains("%2e");
        int slash = path.lastIndexOf('/');
        if (!plain || slash < 0 || !path.substring(slash + 1).equals(fileName)) {
            return false;
        }
        String folder = path.substring(0, slash);
        return switch (vendor) {
            case "temurin" -> host.equals("github.com")
                    && folder.matches("/adoptium/temurin[0-9]{1,3}-binaries/releases/download/[^/]+");
            case "microsoft" -> host.equals("aka.ms") && folder.equals("/download-jdk");
            case "corretto" -> host.equals("corretto.aws") && folder.matches("/downloads/resources/[^/]+")
                    || host.equals(CORRETTO_2019_HOST) && folder.matches("/[^/]+");
            default -> false; // only asked for the vendors above
        };
    }

    /** Where {@link #onVendorHost} expects the vendor's downloads, for a message. */
    private static String vendorHost(String vendor) {
        return switch (vendor) {
            case "temurin" -> "Adoptium's GitHub releases (github.com/adoptium/temurin<N>-binaries/releases/)";
            case "microsoft" -> "aka.ms/download-jdk/";
            default -> "corretto.aws/downloads/resources/";
        };
    }

    /**
     * The download link in a build's foojay answer, null when there is none. For a vendor jkit has
     * keys for it must be on that vendor's own server ({@link #onVendorHost}), or the build is
     * refused: a signature by the vendor's key is checked wherever the file comes from, but a
     * Temurin build from before Adoptium signed has only its checksum, which comes from foojay,
     * and this keeps the file itself coming from Adoptium. A local mirror on this machine
     * ({@code JKIT_SOURCES_FOOJAY_URL=http://localhost...}) is yours, and may serve the files itself.
     */
    private URI download(JsonNode info, Pkg pkg) {
        String url = Json.text(info, "direct_download_uri");
        if (url.isEmpty()) {
            return null;
        }
        URI download = Http.sent(url, "a download", base);
        String vendor = pkg.vendor().foojayName();
        if (vendor.equals("corretto") && download.getRawPath() != null && download.getRawPath().contains("/latest/")) {
            return null; // what packages() skips, should a list name it otherwise
        }
        if (SIGNING_VENDORS.contains(vendor) && "https".equalsIgnoreCase(base.getScheme())
                && !onVendorHost(vendor, download, pkg.fileName())) {
            throw new JkitException(base.getHost() + " lists the download of " + pkg.vendor() + " "
                    + pkg.version() + " at " + Visible.of(download.toString()) + ", not on "
                    + vendorHost(vendor) + "; refusing it",
                    "jkit downloads " + pkg.vendor() + " builds only from there: try again later, or pick another vendor");
        }
        return download;
    }

    /**
     * {@code <download>.sig}: the file name with {@code .sig} added, on the same server. Null when
     * the link has no file name to add it to (no path, or one ending in /), or a query: the vendors
     * jkit has keys for link their downloads by path alone, and adding .sig to the path of a link
     * that names its file in the query asks for something else. A signature is then missing,
     * which for these vendors refuses the install.
     */
    static URI nextTo(URI download) {
        String path = download.getRawPath();
        if (path == null || path.isEmpty() || path.endsWith("/") || download.getRawQuery() != null) {
            return null;
        }
        return URI.create(download.getScheme() + "://" + download.getRawAuthority() + path + ".sig");
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
        Map<String, String> bySynonym = new HashMap<>();
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
            if (pre) {
                // --pre without a version: an early-access major above the newest release, when there is one
                for (int ea = major + 2; ea > major; ea--) {
                    if (!builds(ea, vendor, platform, fx, true).isEmpty()) {
                        major = ea;
                        break;
                    }
                }
            }
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
        // the newest build that can be downloaded directly; some vendors list builds behind a form.
        // A few tries at most: each is a request, and a hostile list could hold thousands
        for (Pkg pkg : candidates.stream().limit(MAX_LOOKUPS).toList()) {
            // this answer carries the download link and often the checksum itself, so https too
            JsonNode info = Json.fetch(http, Http.requireSecure(base.resolve("ids/" + pkg.id())))
                    .path("result").path(0);
            URI download = download(info, pkg);
            if (download == null) {
                continue;
            }
            Checksum checksum = Checksum.of(Json.text(info, "checksum"), Json.text(info, "checksum_type"));
            String checksumUri = Json.text(info, "checksum_uri");
            if (checksum == null && !checksumUri.isEmpty() && !http.offline()) {
                URI uri = Http.sent(checksumUri, "a checksum", base);
                // a file named for its algorithm (.sha256) must hold that kind; checksum_type alone is a hint
                String named = Stream.of("sha512", "sha384", "sha256")
                        .filter(a -> uri.getPath() != null && uri.getPath().endsWith("." + a)).findFirst().orElse(null);
                checksum = named != null
                        ? Checksum.parseStrict(http.getFresh(uri), pkg.fileName(), named)
                        : Checksum.parse(http.getFresh(uri), pkg.fileName(), Json.text(info, "checksum_type"));
            }
            return new Artifact(tool, pkg.version(), pkg.vendor(), download, pkg.fileName(),
                    pkg.archiveType(), checksum == null ? null : checksum.algorithm(),
                    checksum == null ? null : checksum.hash(), fx, signature(info, download, base, pkg));
        }
        Pkg newest = candidates.getFirst();
        if (candidates.size() > MAX_LOOKUPS) {
            Pkg next = candidates.get(MAX_LOOKUPS);
            throw new JkitException(newest.vendor() + " " + newest.version() + " has no direct download, nor do the next "
                    + (MAX_LOOKUPS - 1) + " builds", "an older build may have one: jkit install " + tool.id() + "@"
                    + next.version().raw() + " " + next.vendor().name() + (fx ? " --fx" : "")
                    + (pre && next.version().isPreRelease() ? " --pre" : ""),
                    "or download it from the vendor's site, or pick another vendor");
        }
        throw new JkitException(newest.vendor() + " " + newest.version() + " has no direct download"
                + (candidates.size() > 1 ? ", nor does any other matching build" : ""),
                "download it from the vendor's site, or pick another vendor");
    }

    /**
     * What installing exactly this build would find: one lookup, cached like the version lists.
     * Mirrors {@link #resolve}: no direct download link means none; a checksum counts when foojay
     * gives one or links a checksum file.
     */
    public Availability availability(Pkg pkg) throws IOException {
        JsonNode info = Json.fetch(http, Http.requireSecure(base.resolve("ids/" + pkg.id()))).path("result").path(0);
        URI download = download(info, pkg);
        if (download == null) {
            return Availability.NONE;
        }
        boolean checksum = Checksum.of(Json.text(info, "checksum"), Json.text(info, "checksum_type")) != null
                || !Json.text(info, "checksum_uri").isEmpty();
        return new Availability(pkg, checksum, signature(info, download, base, pkg));
    }

    /**
     * What {@code jkit install <tool>@<versionSpec> <vendor>} would find, given the newest matching
     * build {@code newest}: that build, or like {@link #resolve} the newest matching one with a
     * direct download (Oracle lists its newer JDK 17 updates without one).
     */
    public Availability availability(Pkg newest, String versionSpec, Platform platform, boolean fx, boolean pre)
            throws IOException {
        if (newest.directlyDownloadable()) {
            Availability a = availability(newest);
            if (a.downloadable()) {
                return a;
            }
        }
        String spec = versionSpec == null ? String.valueOf(newest.major()) : versionSpec;
        List<Pkg> candidates = builds(newest.major(), newest.vendor(), platform, fx, pre).stream()
                .filter(p -> p.version().matches(spec))
                .filter(p -> pre || !p.version().isPreRelease())
                .limit(MAX_LOOKUPS) // as many as install looks at
                .toList();
        for (Pkg p : candidates) {
            if (!p.directlyDownloadable() || p.id().equals(newest.id())) {
                continue; // the newest was looked up above
            }
            Availability a = availability(p);
            if (a.downloadable()) {
                return a;
            }
        }
        return Availability.NONE;
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

    /**
     * foojay lists, next to each Corretto release, a catch-all entry per major whose link is
     * corretto.aws/downloads/latest/amazon-corretto-21-x64-linux-jdk.tar.gz: whatever Amazon's
     * newest build is that day, under a bare version ("8", "11", "21"). Not a build jkit can name,
     * pin or tell apart from the real one with the same number. The list carries no links, but
     * these, and only these, are named for their major alone ({@code amazon-corretto-21-...}),
     * where a release is named for its version ({@code amazon-corretto-21.0.9.10.1-...}).
     */
    static boolean latestCorretto(Pkg pkg) {
        return pkg.vendor().foojayName().equals("corretto") && pkg.fileName().matches("amazon-corretto-[0-9]+-.*");
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
                    || !Json.text(n, "filename").matches("[A-Za-z0-9][A-Za-z0-9._+%-]{0,199}")
                    || !Set.of("tar.gz", "tgz", "zip").contains(Json.text(n, "archive_type"))) {
                continue; // not text jkit can use as a folder name
            }
            Pkg pkg = new Pkg(Json.text(n, "id"), Vendor.fromFoojay(Json.text(n, "distribution")),
                    n.path("major_version").asInt(), Version.parse(Json.text(n, "java_version")),
                    Json.text(n, "filename"), Json.text(n, "archive_type"), Json.text(n, "term_of_support"),
                    !n.path("directly_downloadable").isBoolean() || n.path("directly_downloadable").asBoolean());
            if (pkg.vendor().isRetired() || latestCorretto(pkg)) {
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
