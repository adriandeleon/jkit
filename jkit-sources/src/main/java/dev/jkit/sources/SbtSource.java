package dev.jkit.sources;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Version;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sbt launchers. Versions come from Maven Central ({@code org.scala-sbt:sbt}); the zips and their
 * {@code .sha256} files from the sbt GitHub releases. One zip serves every platform.
 *
 * <p>The launcher only starts a build: each project names its own sbt version in
 * {@code project/build.properties}, and the launcher fetches that one itself.
 */
public final class SbtSource {
    public static final URI DEFAULT_DOWNLOADS = URI.create("https://github.com/sbt/sbt/releases/download/");
    private static final String PATH = "org/scala-sbt/sbt/";
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");
    /** Releases and RCs. Milestones and odd builds (1.3.0-M5-94d5ec, 2.0-RC3) have no release zip. */
    private static final Pattern PUBLISHED = Pattern.compile("\\d+\\.\\d+\\.\\d+(-RC\\d+)?");
    /** The first release with a zip on GitHub. Its .sha256 files start at 1.3.5. */
    private static final Version FIRST = Version.parse("1.3.0");

    private final Http http;
    private final URI index;
    private final URI downloads;

    /**
     * @param index a Maven repository root holding {@code org/scala-sbt/sbt/maven-metadata.xml}
     * @param downloads the root holding {@code v<version>/sbt-<version>.zip}
     */
    public SbtSource(Http http, URI index, URI downloads) {
        this.http = http;
        this.index = slash(index);
        this.downloads = slash(downloads);
    }

    public List<Version> versions(boolean pre) throws IOException {
        String xml = http.get(index.resolve(PATH + "maven-metadata.xml"));
        List<Version> out = new ArrayList<>();
        Matcher m = VERSION.matcher(xml);
        while (m.find()) {
            String raw = m.group(1).trim();
            if (!PUBLISHED.matcher(raw).matches()) {
                continue;
            }
            Version v = Version.parse(raw);
            if (v.compareTo(FIRST) >= 0 && (pre || !v.isPreRelease())) {
                out.add(v);
            }
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    public Artifact resolve(String spec, boolean pre) throws IOException {
        Version v = versions(pre).stream()
                .filter(x -> x.matches(spec))
                .findFirst()
                .orElseThrow(() -> noMatch(spec, pre));
        String file = "sbt-" + v.raw() + ".zip";
        URI url = Http.requireSecure(downloads.resolve("v" + v.raw() + "/" + file));
        Checksum checksum = checksum(url, file);
        return new Artifact(Tool.SBT, v, null, url, file, "zip",
                checksum == null ? null : checksum.algorithm(), checksum == null ? null : checksum.hash(), false);
    }

    /** The .sha256 next to the zip; null for the early 1.3.x releases that have none. */
    private Checksum checksum(URI url, String file) throws IOException {
        if (http.offline()) {
            return null;
        }
        try {
            return Checksum.parse(http.getFresh(URI.create(url + ".sha256")), file, "sha256");
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    private JkitException noMatch(String spec, boolean pre) {
        if (!pre) {
            try {
                List<Version> preReleases = versions(true).stream()
                        .filter(Version::isPreRelease).filter(x -> x.matches(spec)).toList();
                if (!preReleases.isEmpty()) {
                    return new JkitException("only pre-releases match sbt@" + spec + ": " + preReleases.getFirst(),
                            "use --pre, or the exact version: jkit install sbt@" + preReleases.getFirst());
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        if (spec != null && Version.looksLikeVersion(spec) && Version.parse(spec).compareTo(FIRST) < 0) {
            return new JkitException("no sbt release matches " + spec,
                    "jkit installs sbt 1.3.0 and later; the launcher still builds projects that pin an older sbt");
        }
        return new JkitException("no sbt release matches " + (spec == null ? "latest" : spec));
    }

    private static URI slash(URI uri) {
        return uri.toString().endsWith("/") ? uri : URI.create(uri + "/");
    }
}
