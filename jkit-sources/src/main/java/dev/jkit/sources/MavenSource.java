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

/** Apache Maven binaries from Maven Central. One zip serves every platform. */
public final class MavenSource {
    public static final URI DEFAULT_BASE = URI.create("https://repo.maven.apache.org/maven2/");
    private static final String PATH = "org/apache/maven/apache-maven/";
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

    private final Http http;
    private final URI base;

    public MavenSource(Http http, URI base) {
        this.http = http;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    }

    public List<Version> versions(boolean pre) throws IOException {
        String xml = http.get(base.resolve(PATH + "maven-metadata.xml"));
        List<Version> out = new ArrayList<>();
        Matcher m = VERSION.matcher(xml);
        while (m.find()) {
            Version v = Version.parse(m.group(1));
            if (pre || !v.isPreRelease()) {
                out.add(v);
            }
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    public Artifact resolve(String spec, boolean pre) throws IOException {
        List<Version> candidates = versions(pre);
        Version v = candidates.stream()
                .filter(x -> x.matches(spec))
                .findFirst()
                .orElseThrow(() -> noMatch(spec, pre));
        String file = "apache-maven-" + v.raw() + "-bin.zip";
        URI url = base.resolve(PATH + v.raw() + "/" + file);
        String sha512 = FoojaySource.firstToken(http.get(URI.create(url + ".sha512")));
        return new Artifact(Tool.MAVEN, v, null, url, file, "zip", "sha512", sha512, false);
    }

    private JkitException noMatch(String spec, boolean pre) {
        if (!pre) {
            try {
                List<Version> preReleases = versions(true).stream()
                        .filter(Version::isPreRelease).filter(x -> x.matches(spec)).toList();
                if (!preReleases.isEmpty()) {
                    return new JkitException("only pre-releases match maven@" + spec + ": " + preReleases.getFirst(),
                            "use --pre, or the exact version: jkit install maven@" + preReleases.getFirst());
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        return new JkitException("no Maven release matches " + (spec == null ? "latest" : spec));
    }
}
