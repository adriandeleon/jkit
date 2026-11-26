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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** JDK builds from the foojay Disco API (https://api.foojay.io/disco/v3.0). */
public final class FoojaySource {
    /**
     * How many builds an install looks up, newest first, to find one with a direct download. Oracle
     * lists its newer JDK 17 updates without one (they need a licence click-through), and that list
     * grows by a build each quarter. 32 covers years of that, while still bounding the requests a
     * hostile list can cause.
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
         * Whether its signature was made with a key the vendor has retired, which install can't
         * check. That is the case for Amazon's 2019 Corretto builds, which are exactly the builds it
         * still serves from its old CloudFront host.
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
     * Every build of one major version from one vendor, newest first. Builds the answer files under
     * another vendor or major are dropped: the vendor decides which key a signature must be made by
     * (or whether jkit looks for one at all), and the version decides whether Temurin signed it.
     * Otherwise a list that answered a question about Temurin 21 with a build it calls Zulu, or with
     * Temurin 17.0.3, could make jkit skip the signature check.
     */
    public List<Pkg> builds(int major, Vendor vendor, Platform platform, boolean fx, boolean pre)
            throws IOException {
        String status = pre ? "release_status=ga&release_status=ea" : "release_status=ga";
        List<Pkg> all = packages(platform,
                "jdk_version=" + major + "&distribution="
                        + URLEncoder.encode(vendor.foojayName(), StandardCharsets.UTF_8)
                        + "&" + status, fx);
        // The answer uses foojay's own vendor names, and requireKnown has already turned the
        // vendor asked for into one of those, so the names compare as they are.
        all.removeIf(p -> !p.vendor().foojayName().equals(vendor.foojayName())
                || p.major() != major || p.version().part(0) != major);
        all.sort(Comparator.comparing(Pkg::version).reversed());
        return all;
    }

    /**
     * The package's signature link. Temurin, Microsoft and Corretto, the vendors jkit has keys for,
     * publish a {@code .sig} next to each download they sign (on Temurin's GitHub releases, aka.ms
     * and corretto.aws). For their signed builds jkit uses the link next to the download, whether or
     * not foojay lists one: foojay leaves it out for some Temurin builds that have one, and a
     * compromised foojay could leave it out on purpose, or point the download elsewhere, to get a
     * build installed on its own checksum alone. Wherever the download is, the signature next to it
     * must still be made by the vendor's key. A download link with no file name to add .sig to is
     * refused, since without a signature such a build would install on foojay's checksum.
     * <p>
     * For other builds, this is the link foojay lists, if it is https. A listed link that isn't
     * https is refused for a vendor jkit has keys for, and dropped for the others, since jkit never
     * fetches their signatures.
     */
    static URI signature(JsonNode info, URI download, URI base, Pkg pkg) {
        if (vendorSigns(pkg.vendor(), pkg.version())) {
            URI next = nextTo(download);
            if (next == null) {
                throw new JkitException(base.getHost() + " lists the download of " + pkg.vendor() + " " + pkg.version()
                        + " as " + Visible.of(download.toString()) + ", where jkit can't find its signature; refusing it",
                        base.equals(DEFAULT_BASE) ? "try again later, or pick another vendor"
                                : "your foojay mirror (JKIT_SOURCES_FOOJAY_URL) must link each file by its path,"
                                        + " with its .sig next to it; or pick another vendor");
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
     * build, and Temurin's from mid-2022 on. Not all of them have one jkit can check: Amazon signed
     * its 2019 Corretto builds with keys it has since retired, and a few old Corretto downloads (the
     * 2020 Alpine builds, one macOS 8u242) have no .sig at all. Like any build whose signature can't
     * be checked, those are refused unless --allow-unverified is given.
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
     * Whether {@code download} is where {@code vendor} publishes its builds, in the exact shape of
     * the vendor's own links and ending in the build's file name. Only asked for the vendors jkit
     * has keys for: a release asset in one of Adoptium's temurin&lt;N&gt;-binaries repositories,
     * aka.ms/download-jdk/, or corretto.aws/downloads/resources/&lt;version&gt;/ (or
     * &lt;version&gt;/ on Amazon's old CloudFront host, which still serves its 2019 builds). foojay
     * lists every build of theirs at one of these. Checking the host alone isn't enough: github.com
     * serves any commit of a fork from the original repository's path
     * (/adoptium/&lt;repo&gt;/archive/&lt;sha&gt;.tar.gz), whoever pushed it.
     * <p>
     * Only the address foojay names is checked. The vendor's server then redirects (aka.ms to
     * download.visualstudio.microsoft.com, GitHub to its release-assets host); foojay doesn't decide
     * those hops, and Http checks that each one stays https.
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
            default -> false; // callers ask only about the vendors above
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
     * The download link in a build's foojay answer, or null when there is none. For a vendor jkit
     * has keys for, the link must be on that vendor's own server ({@link #onVendorHost}), or the
     * build is refused. A signature by the vendor's key would be checked wherever the file comes
     * from, but a Temurin build from before Adoptium started signing has only its checksum, which
     * also comes from foojay; requiring Adoptium's server keeps the file itself coming from Adoptium.
     * <p>
     * A foojay mirror you run ({@code JKIT_SOURCES_FOOJAY_URL}) may serve the builds the vendor
     * signed from its own server, with their .sig next to them. The signature, which install then
     * requires, and the version check after it make those safe, not the host. Builds without a
     * signature must keep the vendor's links, unless the mirror runs on this machine
     * ({@code http://localhost...}): then it may serve those too.
     */
    private URI download(JsonNode info, Pkg pkg) {
        String url = Json.text(info, "direct_download_uri");
        if (url.isEmpty()) {
            return null;
        }
        URI download = Http.sent(url, "a download", base);
        String vendor = pkg.vendor().foojayName();
        if (vendor.equals("corretto") && download.getRawPath() != null && download.getRawPath().contains("/latest/")) {
            // The catch-all /latest/ entry that packages() skips (see latestCorretto), in case a
            // list names it under a file name that check misses.
            return null;
        }
        boolean mirror = !base.equals(DEFAULT_BASE);
        boolean pinned = SIGNING_VENDORS.contains(vendor) && "https".equalsIgnoreCase(base.getScheme())
                && (!mirror || !vendorSigns(pkg.vendor(), pkg.version()));
        if (pinned && !onVendorHost(vendor, download, pkg.fileName())) {
            throw new JkitException(base.getHost() + " lists the download of " + pkg.vendor() + " "
                    + pkg.version() + " at " + Visible.of(download.toString()) + ", not on "
                    + vendorHost(vendor) + "; refusing it", mirror
                    ? "jkit downloads " + pkg.vendor() + " builds the vendor didn't sign only from there: your foojay"
                            + " mirror (JKIT_SOURCES_FOOJAY_URL) must keep their links, or pick another version or vendor"
                    : "jkit downloads " + pkg.vendor() + " builds only from there: try again later, or pick another vendor");
        }
        return download;
    }

    /**
     * The link {@code <download>.sig}: the same server, with {@code .sig} added to the file name.
     * Null when the link has no file name to add it to (no path, or one ending in /), or has a
     * query. The vendors jkit has keys for link their downloads by path alone, and adding .sig to
     * the path of a link that names its file in the query would ask for something else. The
     * signature then counts as missing, which for these vendors refuses the install.
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
        throw new JkitException(2, message, hints.toArray(String[]::new)); // exit code 2: a bad argument
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
                // --pre without a version: use an early-access major above the newest release, if any
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
        // Take the newest build that can be downloaded directly, since some vendors list builds
        // behind a licence form. Try at most MAX_LOOKUPS: each is a request, and a hostile list
        // could hold thousands.
        for (Pkg pkg : candidates.stream().limit(MAX_LOOKUPS).toList()) {
            // This answer carries the download link and often the checksum, so it must be https too.
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
                // A file named for its algorithm (.sha256) must hold that kind; checksum_type is a hint.
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
     * What installing exactly this build would find, with one lookup that is cached like the version
     * lists. It follows {@link #resolve}: no direct download link means there is no download, and a
     * checksum counts when foojay gives one or links a checksum file.
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
     * Whether this is one of foojay's catch-all Corretto entries. Next to the Corretto releases,
     * foojay lists one entry per major whose link is
     * corretto.aws/downloads/latest/amazon-corretto-21-x64-linux-jdk.tar.gz: whatever Amazon's newest
     * build is that day, under a bare version ("8", "11", "21"). jkit can't name it, pin it or tell
     * it apart from the real release with the same number, so it is skipped. The package list has
     * no links, but these entries, and only these, have file names with the major alone
     * ({@code amazon-corretto-21-...}), where a release has its full version
     * ({@code amazon-corretto-21.0.9.10.1-...}).
     */
    static boolean latestCorretto(Pkg pkg) {
        return pkg.vendor().foojayName().equals("corretto") && pkg.fileName().matches("amazon-corretto-[0-9]+-.*");
    }

    /**
     * The version jkit shows and installs a build under: foojay's java_version, except for Mandrel.
     * foojay gives every Mandrel build of a feature release just the feature number ("21" for each
     * Mandrel 23.1.x, "25" for each 25.0.x), which would make them all one build, so Mandrel's own
     * release goes after a + ("21+23.1.5"). The feature release stays first, where jkit looks for
     * it, so graalvm@21 still matches, and the builds sort as build numbers do: an install made
     * under the bare number ("21") counts as the oldest, and jkit upgrade offers the newest.
     */
    static String version(String distribution, String javaVersion, String distributionVersion) {
        if (distribution.equals("mandrel") && javaVersion.matches("[0-9]{1,3}") && !distributionVersion.isEmpty()
                && !distributionVersion.contains("+")) {
            return javaVersion + "+" + distributionVersion;
        }
        return javaVersion;
    }

    private static final Pattern CORRETTO_FILE = Pattern.compile("amazon-corretto-([0-9]+(?:\\.[0-9]+)*)[-_].*");

    /**
     * The vendor's own number for a build, which tells respins of one version apart: foojay's
     * distribution_version, or for Corretto the version its file is named for, since foojay gives
     * both 11.0.20.8.1 and 11.0.20.9.1 the distribution_version 11.0.20. "0" when there is none.
     */
    static String respin(String distribution, String fileName, String distributionVersion) {
        Matcher m = CORRETTO_FILE.matcher(fileName);
        if (distribution.equals("corretto") && m.matches()) {
            return m.group(1);
        }
        return distributionVersion.isEmpty() ? "0" : distributionVersion;
    }

    private List<Pkg> packages(Platform platform, String extra, boolean fx) throws IOException {
        String query = "packages?operating_system=" + platform.os()
                + "&architecture=" + platform.arch()
                + "&archive_type=tar.gz&archive_type=zip&package_type=jdk"
                + "&javafx_bundled=" + fx + "&" + extra;
        JsonNode root = Json.fetch(http, base.resolve(query));
        String preferred = platform.isWindows() ? "zip" : "tar.gz";
        // Keep one package per vendor and version: the vendor's newest respin of it (see below), in
        // the platform's usual archive type when both types are listed.
        Map<String, Pkg> byKey = new LinkedHashMap<>();
        Map<String, Version> respin = new HashMap<>();
        for (JsonNode n : root.path("result")) {
            if (platform.isLinux() && !Json.text(n, "lib_c_type").equals(platform.libc())) {
                continue;
            }
            if (n.path("feature").size() > 0) {
                continue; // skip CRaC, Leyden and other variant builds
            }
            if (n.path("javafx_bundled").asBoolean() != fx) {
                continue;
            }
            String distribution = Json.text(n, "distribution");
            String own = Json.text(n, "distribution_version");
            own = Version.isSafeText(own) && Version.looksLikeVersion(own) ? own : "";
            String version = version(distribution, Json.text(n, "java_version"), own);
            if (!Version.isSafeText(version) || !Vendor.isSafeName(distribution)
                    || !Json.text(n, "id").matches("[A-Za-z0-9_-]{1,128}")
                    || !Json.text(n, "filename").matches("[A-Za-z0-9][A-Za-z0-9._+%-]{0,199}")
                    || !Set.of("tar.gz", "tgz", "zip").contains(Json.text(n, "archive_type"))) {
                continue; // fields jkit can't safely use in a folder name, a file name or a request
            }
            Pkg pkg = new Pkg(Json.text(n, "id"), Vendor.fromFoojay(distribution),
                    n.path("major_version").asInt(), Version.parse(version),
                    Json.text(n, "filename"), Json.text(n, "archive_type"), Json.text(n, "term_of_support"),
                    !n.path("directly_downloadable").isBoolean() || n.path("directly_downloadable").asBoolean());
            if (pkg.vendor().isRetired() || latestCorretto(pkg)) {
                continue;
            }
            // Corretto, Zulu, Liberica and others list a respin under the version it rebuilds
            // (Corretto 11.0.20.8.1 and 11.0.20.9.1 are both 11.0.20). Take the newest by the
            // vendor's own number, not whichever one foojay happens to list first.
            String key = pkg.vendor().foojayName() + "|" + pkg.version().raw();
            Pkg existing = byKey.get(key);
            Version number = Version.parse(respin(distribution, pkg.fileName(), own));
            int newer = existing == null ? 1 : number.compareTo(respin.get(key));
            if (newer > 0 || newer == 0 && !existing.archiveType().equals(preferred) && pkg.archiveType().equals(preferred)) {
                byKey.put(key, pkg);
                respin.put(key, number);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
