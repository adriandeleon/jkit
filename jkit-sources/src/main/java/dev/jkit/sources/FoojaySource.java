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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
            String host = pkg == null ? null : RETIRED_KEY_HOSTS.get(pkg.vendor().foojayName());
            return host != null && signature != null && host.equalsIgnoreCase(signature.getHost());
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

    /**
     * The JDK vendors jkit has signing keys for. The one list: Signatures in jkit-core refuses a
     * vendor key for any other, and the tests check that it has a key for each of these, and that
     * {@link #vendorSigns}, {@link #onVendorHost} and {@link #vendorHost} know each of them.
     */
    public static final Set<String> SIGNING_VENDORS = Set.of("temurin", "microsoft", "corretto");

    /**
     * The vendors among {@link #SIGNING_VENDORS} that signed some builds with keys they have since
     * retired, and the host that serves exactly those builds. Signatures' retired key IDs are for
     * the same vendors.
     */
    public static final Map<String, String> RETIRED_KEY_HOSTS = Map.of("corretto", CORRETTO_2019_HOST);

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
     * The vendors whose downloads must be on the vendor's own server ({@link #onVendorHost}): those
     * jkit has keys for, and the others whose download servers are stable enough to name. For
     * those others the checksum comes from foojay too, so without this a compromised foojay could
     * pick both the file and its checksum. Each was checked against the download links foojay
     * listed for it in November 2026 (over 2,500 builds across versions, platforms and JavaFX).
     */
    static final Set<String> PINNED_VENDORS = Set.of("temurin", "microsoft", "corretto", "zulu", "liberica",
            "sap_machine", "semeru", "semeru_certified", "oracle", "graalvm_community", "graalvm", "mandrel");

    /**
     * Whether {@code download} is where {@code vendor} publishes its builds, in the exact shape of
     * the vendor's own links and ending in the build's file name. Only asked for
     * {@link #PINNED_VENDORS}: a release asset in one of Adoptium's temurin&lt;N&gt;-binaries
     * repositories, aka.ms/download-jdk/, corretto.aws/downloads/resources/&lt;version&gt;/ (or
     * &lt;version&gt;/ on Amazon's old CloudFront host, which still serves its 2019 builds), Azul's
     * cdn.azul.com/zulu/bin/ (zulu-embedded/bin/ for some ARM builds), a release asset of
     * bell-sw/Liberica, SAP/SapMachine, IBM's semeru&lt;N&gt;-binaries (and -certified-binaries),
     * graalvm/graalvm-ce-builds or graalvm/mandrel on GitHub, or Oracle's download.oracle.com
     * (/java/, /graalvm/) or its gds.oracle.com download service, whose links name an artifact,
     * not a file. foojay lists every build of these vendors at one of these. Checking the host alone
     * isn't enough on GitHub: github.com serves any commit of a fork from the original
     * repository's path (/adoptium/&lt;repo&gt;/archive/&lt;sha&gt;.tar.gz), whoever pushed it.
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
        if (!plain || slash < 0) {
            return false;
        }
        if ((vendor.equals("graalvm_community") || vendor.equals("graalvm")) && host.equals("gds.oracle.com")
                && path.matches("/api/[0-9]{8}/artifacts/[0-9A-Fa-f]{32}/content")) {
            return true; // Oracle's download service: the link names the artifact, not the file
        }
        // GitHub writes the + of a file name as %2B (sapmachine-jdk-11%2B28_osx-x64_bin.tar.gz)
        String name = path.substring(slash + 1);
        if (!name.equals(fileName) && !name.replaceAll("%2[Bb]", "+").equals(fileName)) {
            return false;
        }
        String folder = path.substring(0, slash);
        String release = "/releases/download/[^/]+";
        return switch (vendor) {
            case "temurin" -> host.equals("github.com") && folder.matches("/adoptium/temurin[0-9]{1,3}-binaries" + release);
            case "microsoft" -> host.equals("aka.ms") && folder.equals("/download-jdk");
            case "corretto" -> host.equals("corretto.aws") && folder.matches("/downloads/resources/[^/]+")
                    || host.equals(CORRETTO_2019_HOST) && folder.matches("/[^/]+");
            case "zulu" -> host.equals("cdn.azul.com") && (folder.equals("/zulu/bin") || folder.equals("/zulu-embedded/bin"));
            case "liberica" -> host.equals("github.com") && folder.matches("/bell-sw/Liberica" + release)
                    || host.equals("download.bell-sw.com") && folder.matches("(/[^/]+)+");
            case "sap_machine" -> host.equals("github.com") && folder.matches("/SAP/SapMachine" + release);
            case "semeru", "semeru_certified" -> host.equals("github.com")
                    && folder.matches("/ibmruntimes/semeru[0-9]{1,3}(-certified)?-binaries" + release);
            case "oracle" -> host.equals("download.oracle.com") && folder.matches("/java(/[^/]+)+");
            case "graalvm_community" -> host.equals("github.com") && folder.matches("/graalvm/graalvm-ce-builds" + release);
            case "graalvm" -> host.equals("download.oracle.com") && folder.matches("/graalvm(/[^/]+)+");
            case "mandrel" -> host.equals("github.com") && folder.matches("/graalvm/mandrel" + release);
            default -> false; // callers ask only about the vendors above
        };
    }

    /** Where {@link #onVendorHost} expects the vendor's downloads, for a message. */
    static String vendorHost(String vendor) {
        return switch (vendor) {
            case "temurin" -> "Adoptium's GitHub releases (github.com/adoptium/temurin<N>-binaries/releases/)";
            case "microsoft" -> "aka.ms/download-jdk/";
            case "corretto" -> "corretto.aws/downloads/resources/";
            case "zulu" -> "cdn.azul.com/zulu/bin/";
            case "liberica" -> "BellSoft's GitHub releases (github.com/bell-sw/Liberica/releases/)";
            case "sap_machine" -> "SAP's GitHub releases (github.com/SAP/SapMachine/releases/)";
            case "semeru", "semeru_certified" -> "IBM's GitHub releases (github.com/ibmruntimes/semeru<N>-binaries/releases/)";
            case "oracle" -> "download.oracle.com/java/";
            case "graalvm_community" -> "GraalVM's GitHub releases (github.com/graalvm/graalvm-ce-builds/releases/)"
                    + " or gds.oracle.com";
            case "graalvm" -> "download.oracle.com/graalvm/ or gds.oracle.com";
            case "mandrel" -> "Mandrel's GitHub releases (github.com/graalvm/mandrel/releases/)";
            default -> throw new IllegalArgumentException("no download host known for " + vendor);
        };
    }

    /**
     * The download link in a build's foojay answer, or null when there is none. For
     * {@link #PINNED_VENDORS}, the link must be on that vendor's own server ({@link #onVendorHost}),
     * or the build is refused. A signature by the vendor's key would be checked wherever the file
     * comes from, but a Temurin build from before Adoptium started signing, like every build of a
     * vendor jkit has no key for, has only its checksum, which also comes from foojay; requiring
     * the vendor's server keeps the file itself coming from the vendor.
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
        boolean pinned = PINNED_VENDORS.contains(vendor) && "https".equalsIgnoreCase(base.getScheme())
                && (!mirror || !vendorSigns(pkg.vendor(), pkg.version()));
        if (pinned && !onVendorHost(vendor, download, pkg.fileName())) {
            String unsigned = SIGNING_VENDORS.contains(vendor) ? " builds the vendor didn't sign"
                    : " builds, which jkit has no key to check,";
            throw new JkitException(base.getHost() + " lists the download of " + pkg.vendor() + " "
                    + pkg.version() + " at " + Visible.of(download.toString()) + ", not on "
                    + vendorHost(vendor) + "; refusing it", mirror
                    ? "jkit downloads " + pkg.vendor() + unsigned + " only from there: your foojay"
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
     * <p>
     * The vendor decides which key a signature must be made by and which server a download must
     * come from, so foojay's synonyms can only add names, never move one: the vendors jkit has keys
     * for are known without asking foojay; a distribution's own name, or a name jkit reads as
     * another one (adoptium is temurin), always means that one; and a synonym two distributions
     * both claim is ignored. Otherwise a list naming "temurin" a synonym of Zulu would make
     * {@code jkit install jdk temurin} install Zulu builds without Temurin's signature.
     *
     * @param source how the name was given, for the message: "" when typed, else e.g. "your
     *               default vendor (jdk.vendor)"
     */
    public Vendor requireKnown(Vendor vendor, String typed, String source) throws IOException {
        Vendor typedVendor = Vendor.of(typed);
        for (Vendor local : List.of(vendor, typedVendor)) {
            if (SIGNING_VENDORS.contains(local.foojayName())) {
                return Vendor.fromFoojay(local.foojayName());
            }
        }
        JsonNode root = Json.fetch(http, base.resolve(
                "distributions?include_versions=false&include_synonyms=true"));
        Map<String, JsonNode> listed = new LinkedHashMap<>();
        for (JsonNode d : root.path("result")) {
            String param = Json.text(d, "api_parameter");
            if (Vendor.isSafeName(param) && !Vendor.fromFoojay(param).isRetired()) {
                listed.putIfAbsent(param, d);
            }
        }
        TreeSet<String> names = new TreeSet<>();
        for (String param : listed.keySet()) {
            names.add(Vendor.fromFoojay(param).name());
        }
        // exact names first: foojay's own, and the name jkit reads the text as
        for (Vendor local : List.of(vendor, typedVendor)) {
            if (listed.containsKey(local.foojayName())) {
                return Vendor.fromFoojay(local.foojayName());
            }
        }
        Map<String, String> bySynonym = new HashMap<>();
        Set<String> claimedTwice = new HashSet<>();
        for (Map.Entry<String, JsonNode> d : listed.entrySet()) {
            for (JsonNode syn : d.getValue().path("synonyms")) {
                String name = syn.asText().toLowerCase(Locale.ROOT).trim();
                // what jkit makes of the name by itself: an alias (adoptium, sapmachine) if it is one
                String local = Vendor.of(name).foojayName();
                boolean alias = !local.equals(name.replace('-', '_'));
                if (name.isEmpty() || SIGNING_VENDORS.contains(local)
                        || (alias || listed.containsKey(local)) && !local.equals(d.getKey())) {
                    continue; // a name that already means a vendor: a synonym can't move it
                }
                String other = bySynonym.putIfAbsent(name, d.getKey());
                if (other != null && !other.equals(d.getKey())) {
                    claimedTwice.add(name);
                }
            }
        }
        bySynonym.keySet().removeAll(claimedTwice);
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
        List<Pkg> candidates = offlineCandidates(tool, versionSpec, vendor, platform, fx, pre, major);
        if (candidates == null) {
            candidates = builds(major, vendor, platform, fx, pre).stream()
                    .filter(p -> p.version().matches(versionSpec))
                    .filter(p -> pre || !p.version().isPreRelease())
                    .toList();
        }
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
     * Offline, when the vendor's list of builds for {@code major} isn't cached: the vendor's newest
     * build of it from the list of each vendor's newest builds, which {@code jkit search} caches,
     * if that one matches. Null online, or when the vendor's list is cached: then the caller asks
     * for that list as usual.
     */
    private List<Pkg> offlineCandidates(Tool tool, String versionSpec, Vendor vendor, Platform platform, boolean fx,
                                        boolean pre, int major) throws IOException {
        if (!http.offline()) {
            return null;
        }
        try {
            builds(major, vendor, platform, fx, pre);
            return null; // cached
        } catch (IOException | JkitException notCached) {
            List<Pkg> newest;
            try {
                newest = newestPerVendorAndMajor(platform, fx, pre).stream()
                        // the same checks as builds(): the vendor and version decide which checks apply
                        .filter(p -> p.vendor().foojayName().equals(vendor.foojayName()) && p.major() == major
                                && p.version().part(0) == major)
                        .filter(p -> p.version().matches(versionSpec) && (pre || !p.version().isPreRelease()))
                        .toList();
            } catch (IOException | JkitException e) {
                newest = List.of();
            }
            if (newest.isEmpty()) {
                throw new JkitException("--offline, but the list of " + vendor + " " + major + " builds isn't cached",
                        "fill the cache: jkit search " + tool.id() + "@" + major + " " + vendor.name()
                                + (fx ? " --fx" : "") + (pre ? " --pre" : "") + " (without --offline)");
            }
            return newest;
        }
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

    /** How many vendors' build lists a "no build matching" hint asks for at once. */
    static final int NO_MATCH_THREADS = 4;

    /**
     * The error for a version {@code vendor} doesn't have, with a hint: the other vendors that have
     * it, or, when every vendor was asked and none has it, what the newest build of the major is.
     * A vendor whose list couldn't be asked (offline and not cached, a failed request, or past
     * {@link #MAX_LOOKUPS}) is unknown, not "doesn't have it": then the hint names the vendors
     * with builds of the major instead of claiming nobody has the version.
     */
    private JkitException noMatch(Tool tool, String spec, Vendor vendor, Platform platform,
                                  boolean fx, int major) {
        String what = tool.id() + (spec == null ? "" : "@" + spec);
        String message = vendor + " has no build matching " + what + " for " + platform;
        try {
            TreeSet<String> others = new TreeSet<>();
            TreeSet<String> withMajor = new TreeSet<>();
            Version newest = null;
            TreeSet<String> newestFrom = new TreeSet<>();
            List<Pkg> toAsk = new ArrayList<>();
            boolean unknown = false;
            for (Pkg p : newestPerVendorAndMajor(platform, fx)) {
                if (p.major() != major || (tool == Tool.GRAALVM && !p.vendor().isGraalVmBased())) {
                    continue;
                }
                int c = newest == null ? 1 : p.version().compareTo(newest);
                if (c > 0) {
                    newest = p.version();
                    newestFrom.clear();
                }
                if (c >= 0) {
                    newestFrom.add(p.vendor().name());
                }
                if (p.vendor().equals(vendor)) {
                    continue;
                }
                withMajor.add(p.vendor().name());
                // the list has each vendor's newest build only: one whose newest is past the spec
                // (21.0.9 for jdk@21.0.5) may still have a matching older build, which takes a lookup
                if (p.version().matches(spec)) {
                    others.add(p.vendor().name());
                } else if (spec != null && p.version().compareTo(Version.parse(spec)) > 0) {
                    if (http.offline() || toAsk.size() >= MAX_LOOKUPS) {
                        unknown = true; // not asked: offline, extra lookups are skipped
                    } else {
                        toAsk.add(p);
                    }
                }
            }
            unknown |= ask(toAsk, major, platform, fx, spec, others);
            if (!others.isEmpty()) {
                return new JkitException(message, "available from: " + String.join(", ", others));
            }
            if (newest != null && spec != null && !unknown) {
                // nobody has it, so a list of vendors would mislead: say what the newest build is
                return new JkitException(message, "no other vendor has " + what + " either; the newest "
                        + tool + " " + major + " is " + newest + " (" + String.join(", ", newestFrom) + ")",
                        "see them all: jkit search " + tool.id() + "@" + major);
            }
            if (!withMajor.isEmpty() && spec != null) {
                // some vendors couldn't be asked: name who has the major, not who has the version
                return new JkitException(message, "other vendors with " + (tool == Tool.GRAALVM ? "GraalVM " : "JDK ")
                        + major + ": " + String.join(", ", withMajor), "see them all: jkit search " + tool.id() + "@" + major);
            }
        } catch (IOException | JkitException ignored) {
            // fall through to the plain message
        }
        return new JkitException(message);
    }

    /**
     * Asks each of {@code vendors} for its builds of {@code major}, {@link #NO_MATCH_THREADS} at a
     * time, adding those with a build matching {@code spec} to {@code have}. Returns whether any
     * couldn't be asked.
     */
    private boolean ask(List<Pkg> vendors, int major, Platform platform, boolean fx, String spec, Set<String> have) {
        if (vendors.isEmpty()) {
            return false;
        }
        Set<String> found = ConcurrentHashMap.newKeySet();
        AtomicBoolean failed = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(NO_MATCH_THREADS, vendors.size()), r -> {
            Thread t = new Thread(r, "jkit-vendor-lookup");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Pkg p : vendors) {
                futures.add(pool.submit(() -> {
                    try {
                        if (builds(major, p.vendor(), platform, fx, false).stream().anyMatch(b -> b.version().matches(spec))) {
                            found.add(p.vendor().name());
                        }
                    } catch (IOException | RuntimeException e) {
                        failed.set(true); // that vendor's list failed: unknown, not "doesn't have it"
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    failed.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed.set(true);
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        have.addAll(found);
        return failed.get();
    }

    /**
     * How far a vendor's newest build of a major lags the newest of that major elsewhere.
     *
     * @param newest  the newest build of the major any vendor has
     * @param vendors the vendors that have it, by name
     */
    public record Behind(Version newest, List<String> vendors) { }

    /** How many updates a vendor's newest build must lag to count as {@link Behind}. */
    static final int BEHIND_UPDATES = 2;

    /**
     * The builds of {@code index} (each vendor's newest per major, {@link #newestPerVendorAndMajor})
     * that are at least {@link #BEHIND_UPDATES} updates older than the newest release of their
     * major from another vendor: a vendor that stopped updating that release, such as openjdk
     * (Oracle's jdk.java.net builds), which publishes only the first updates of each release. A
     * vendor a single update behind is likely just later with the newest one. Pre-releases, and
     * Mandrel, whose versions carry only the feature number ({@link #version}), don't count.
     */
    public static Map<Pkg, Behind> behind(List<Pkg> index) {
        Map<Integer, Pkg> newest = new HashMap<>();
        for (Pkg p : index) {
            if (comparable(p)) {
                newest.merge(p.major(), p, (a, b) -> update(b) > update(a) ? b : a);
            }
        }
        Map<Pkg, Behind> out = new HashMap<>();
        for (Pkg p : index) {
            Pkg top = newest.get(p.major());
            if (top == null || !comparable(p) || update(top) - update(p) < BEHIND_UPDATES) {
                continue;
            }
            TreeSet<String> from = new TreeSet<>();
            for (Pkg q : index) {
                if (q.major() == p.major() && comparable(q) && update(q) == update(top)) {
                    from.add(q.vendor().name());
                }
            }
            out.put(p, new Behind(top.version(), List.copyOf(from)));
        }
        return out;
    }

    private static boolean comparable(Pkg p) {
        return !p.version().isPreRelease() && !p.vendor().foojayName().equals("mandrel");
    }

    /** The interim and update numbers of a build as one number: 21.0.9 is 9, 8.0.462 is 462. */
    private static long update(Pkg p) {
        return (long) p.version().part(1) * 100_000 + p.version().part(2);
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
