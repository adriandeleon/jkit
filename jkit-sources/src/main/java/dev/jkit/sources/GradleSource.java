package dev.jkit.sources;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Version;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Gradle distributions from services.gradle.org. One zip serves every platform. */
public final class GradleSource {
    public static final URI DEFAULT_BASE = URI.create("https://services.gradle.org/");

    private record Release(Version version, String url, String checksum, String checksumUrl) { }

    private final Http http;
    private final URI base;

    public GradleSource(Http http, URI base) {
        this.http = http;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    }

    public List<Version> versions(boolean pre) throws IOException {
        return releases(pre).stream().map(Release::version).toList();
    }

    public Artifact resolve(String spec, boolean pre) throws IOException {
        Release r = releases(pre).stream()
                .filter(x -> x.version().matches(spec))
                .findFirst()
                .orElseThrow(() -> new JkitException("no Gradle release matches " + (spec == null ? "latest" : spec)));
        String checksum = r.checksum();
        if (checksum.isEmpty() && !r.checksumUrl().isEmpty()) {
            checksum = FoojaySource.firstToken(http.get(URI.create(r.checksumUrl())));
        }
        String url = r.url();
        return new Artifact(Tool.GRADLE, r.version(), null, URI.create(url),
                url.substring(url.lastIndexOf('/') + 1), "zip",
                checksum.isEmpty() ? null : "sha256", checksum.isEmpty() ? null : checksum);
    }

    private List<Release> releases(boolean pre) throws IOException {
        JsonNode root = Json.parse(http.get(base.resolve("versions/all")));
        List<Release> out = new ArrayList<>();
        for (JsonNode n : root) {
            if (n.path("snapshot").asBoolean() || n.path("nightly").asBoolean()
                    || n.path("releaseNightly").asBoolean() || n.path("broken").asBoolean()) {
                continue;
            }
            boolean isPre = !Json.text(n, "rcFor").isEmpty() || !Json.text(n, "milestoneFor").isEmpty();
            if (isPre && !pre) {
                continue;
            }
            out.add(new Release(Version.parse(Json.text(n, "version")), Json.text(n, "downloadUrl"),
                    Json.text(n, "checksum"), Json.text(n, "checksumUrl")));
        }
        out.sort(Comparator.comparing(Release::version).reversed());
        return out;
    }
}
