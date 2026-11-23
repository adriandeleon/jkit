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
import dev.jkit.sources.SbtSource;
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
    private final boolean refresh;
    private final boolean offline;
    /** Built on first use: the HTTP client costs startup time that status, env and the cd hook don't need. */
    private Http http;
    final PrintStream out;
    final PrintStream err = System.err;
    /** Why config.toml couldn't be read, for a lenient context (doctor); otherwise always null. */
    final String configError;
    /** Whether the jkit home existed when the command started, so a command can say it created it. */
    final boolean homeExisted;
    /** Set once a {@link Changes} has reported creating the home. */
    boolean homeReported;
    /** Version lists the cache wrote during this command, not yet reported; see {@link Changes#disk}. */
    final java.util.List<CacheWrite> cacheWrites = new java.util.ArrayList<>();

    /** One cached version list written: {@code created} false when it replaced an older copy. */
    record CacheWrite(URI source, java.nio.file.Path file, boolean created) { }

    private Context(Map<String, String> env, boolean refresh, boolean offline, PrintStream out) {
        this(env, refresh, offline, out, false);
    }

    private Context(Map<String, String> env, boolean refresh, boolean offline, PrintStream out, boolean lenient) {
        this.env = env;
        this.out = out;
        this.home = JkitHome.fromEnv(env);
        this.homeExisted = java.nio.file.Files.exists(home.root());
        this.platform = Platform.current();
        Config loaded;
        String error = null;
        home.requireSafe(); // never lenient: a home others can write to puts their programs on PATH
        try {
            loaded = Config.load(home, env);
        } catch (dev.jkit.model.JkitException e) {
            if (!lenient) {
                throw e;
            }
            error = e.getMessage();
            loaded = new Config("temurin", "graalvm-ce");
        }
        this.config = loaded;
        this.configError = error;
        this.store = new Store(home);
        this.os = OsServices.current();
        this.refresh = refresh;
        this.offline = offline;
    }

    /** The caching HTTP client, created on first use. */
    Http http() {
        if (http == null) {
            try {
                home.create(); // before the cache makes it with the default permissions
            } catch (java.io.IOException e) {
                throw new dev.jkit.model.JkitException("cannot create " + home.root() + ": " + e.getMessage());
            }
            http = new CachingHttp(new JdkHttp(env, userAgent(), Duration.ofSeconds(60)), home.index(),
                    Duration.ofHours(24), Clock.systemUTC(), refresh,
                    offline || offlineByEnv(env),
                    new CachingHttp.Listener() {
                        @Override
                        public void stored(URI uri, java.nio.file.Path file, boolean created) {
                            cacheWrites.add(new CacheWrite(uri, file, created));
                        }

                        @Override
                        public void stale(String message) {
                            System.err.println("warning: " + message);
                        }
                    });
        }
        return http;
    }

    /** JKIT_NETWORK_OFFLINE=true, 1 or yes, like the other on/off variables. */
    static boolean offlineByEnv(Map<String, String> env) {
        String v = env.getOrDefault("JKIT_NETWORK_OFFLINE", "").trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    static String userAgent() {
        return "jkit/" + VersionProvider.version();
    }

    static Context create(boolean refresh, boolean offline) {
        return new Context(dev.jkit.model.Envs.system(), refresh, offline, System.out);
    }

    /**
     * For doctor: never online, and a broken config.toml doesn't throw; it is recorded in
     * {@link #configError} and the built-in defaults are used.
     */
    static Context lenient(Map<String, String> env, PrintStream out) {
        return new Context(env, false, true, out, true);
    }

    /**
     * For the read-only commands (status, list, env): a broken config.toml is a warning, not a
     * failure, so they can still say what is going on. Never online.
     */
    static Context readOnly() {
        Context ctx = lenient(dev.jkit.model.Envs.system(), System.out);
        if (ctx.configError != null) {
            ctx.err.println("warning: " + ctx.configError + "; using the built-in defaults");
        }
        return ctx;
    }

    /** For tests: a context over an explicit environment, never online. */
    static Context forEnv(Map<String, String> env) {
        return forEnv(env, System.out);
    }

    /** For tests: like {@link #forEnv(Map)}, printing to {@code out}. */
    static Context forEnv(Map<String, String> env, PrintStream out) {
        return new Context(env, false, true, out);
    }

    FoojaySource foojay() {
        return new FoojaySource(http(), url("JKIT_SOURCES_FOOJAY_URL", FoojaySource.DEFAULT_BASE));
    }

    GradleSource gradle() {
        return new GradleSource(http(), url("JKIT_SOURCES_GRADLE_URL", GradleSource.DEFAULT_BASE));
    }

    MavenSource maven() {
        return new MavenSource(http(), url("JKIT_SOURCES_MAVEN_URL", MavenSource.DEFAULT_BASE));
    }

    /** Versions from the Maven repository; zips from JKIT_SOURCES_SBT_URL (the sbt GitHub releases). */
    SbtSource sbt() {
        return new SbtSource(http(), url("JKIT_SOURCES_MAVEN_URL", MavenSource.DEFAULT_BASE),
                url("JKIT_SOURCES_SBT_URL", SbtSource.DEFAULT_DOWNLOADS));
    }

    Installer installer() {
        return new Installer(home, http());
    }

    /** A source's base URL: https, or http to this machine; a version list over plain http could be altered. */
    URI url(String variable, URI fallback) {
        String value = env.get(variable);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        URI uri;
        try {
            uri = URI.create(value.endsWith("/") ? value : value + "/");
        } catch (IllegalArgumentException e) {
            throw new dev.jkit.model.JkitException(variable + " is not a URL: " + value);
        }
        if (!Http.isSecure(uri)) {
            throw new dev.jkit.model.JkitException(variable + " must be an https URL (or http to localhost), not " + value,
                    "unset it to use the default source");
        }
        return uri;
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
