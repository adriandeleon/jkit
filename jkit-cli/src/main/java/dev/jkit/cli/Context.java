package dev.jkit.cli;

import dev.jkit.core.Config;
import dev.jkit.core.Installer;
import dev.jkit.core.JkitHome;
import dev.jkit.core.Store;
import dev.jkit.model.Platform;
import dev.jkit.platform.OsServices;
import dev.jkit.sources.CachingHttp;
import dev.jkit.sources.FoojaySource;
import dev.jkit.sources.GradleSource;
import dev.jkit.sources.Http;
import dev.jkit.sources.JdkHttp;
import dev.jkit.sources.MavenSource;
import java.io.PrintStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Everything a command needs, built once from the environment. */
final class Context {
    final Map<String, String> env;
    final JkitHome home;
    final Platform platform;
    final Config config;
    final Store store;
    final OsServices os;
    final Http http;
    final PrintStream out = System.out;
    final PrintStream err = System.err;

    private Context(Map<String, String> env, boolean refresh, boolean offline) {
        this.env = env;
        this.home = JkitHome.fromEnv(env);
        this.platform = Platform.current();
        this.config = Config.load(home, env);
        this.store = new Store(home);
        this.os = OsServices.current();
        this.http = new CachingHttp(new JdkHttp(env), home.index(), Duration.ofHours(24),
                Clock.systemUTC(), refresh, offline || "true".equals(env.get("JKIT_NETWORK_OFFLINE")));
    }

    static Context create(boolean refresh, boolean offline) {
        return new Context(System.getenv(), refresh, offline);
    }

    /** For tests: a context over an explicit environment, never online. */
    static Context forEnv(Map<String, String> env) {
        return new Context(env, false, true);
    }

    FoojaySource foojay() {
        return new FoojaySource(http, url("JKIT_SOURCES_FOOJAY_URL", FoojaySource.DEFAULT_BASE));
    }

    GradleSource gradle() {
        return new GradleSource(http, url("JKIT_SOURCES_GRADLE_URL", GradleSource.DEFAULT_BASE));
    }

    MavenSource maven() {
        return new MavenSource(http, url("JKIT_SOURCES_MAVEN_URL", MavenSource.DEFAULT_BASE));
    }

    Installer installer() {
        return new Installer(home, http);
    }

    private URI url(String variable, URI fallback) {
        String value = env.get(variable);
        return value == null || value.isBlank() ? fallback : URI.create(value);
    }

    /** Terminal width from COLUMNS, else 80. */
    int width() {
        try {
            return Math.max(60, Integer.parseInt(env.getOrDefault("COLUMNS", "80")));
        } catch (NumberFormatException e) {
            return 80;
        }
    }
}
