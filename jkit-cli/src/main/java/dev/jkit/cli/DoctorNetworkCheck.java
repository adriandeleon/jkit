package dev.jkit.cli;

import dev.jkit.sources.FoojaySource;
import dev.jkit.sources.GradleSource;
import dev.jkit.sources.JdkHttp;
import dev.jkit.sources.MavenSource;
import dev.jkit.sources.SbtSource;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Doctor's --network check: whether jkit's sources can be reached. */
final class DoctorNetworkCheck {
    private DoctorNetworkCheck() { }

    static void network(DoctorReport r) {
        if (!r.network) {
            return;
        }
        // one quick try each: doctor answers "can I reach it now", not "eventually"
        JdkHttp http = new JdkHttp(r.ctx.env, Context.userAgent(), Duration.ofSeconds(10), 1);
        List<URI> uris = List.of(
                r.ctx.url("JKIT_SOURCES_FOOJAY_URL", FoojaySource.DEFAULT_BASE).resolve("distributions?include_versions=false"),
                r.ctx.url("JKIT_SOURCES_GRADLE_URL", GradleSource.DEFAULT_BASE).resolve("versions/current"),
                r.ctx.url("JKIT_SOURCES_MAVEN_URL", MavenSource.DEFAULT_BASE).resolve("org/apache/maven/apache-maven/maven-metadata.xml"),
                r.ctx.url("JKIT_SOURCES_SBT_URL", SbtSource.DEFAULT_DOWNLOADS).resolve("v1.13.1/sbt-1.13.1.zip.sha256"));
        for (URI uri : uris) {
            long start = System.nanoTime();
            try {
                http.get(uri);
                r.ok("reached " + uri.getHost() + " (" + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms)");
            } catch (Exception e) {
                r.fail("can't reach " + uri.getHost() + ": " + Errors.describe(e).getMessage(),
                        "check your connection; behind a proxy, set HTTPS_PROXY or JKIT_NETWORK_PROXY");
            }
        }
    }
}
