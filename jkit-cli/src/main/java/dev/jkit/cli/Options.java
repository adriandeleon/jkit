package dev.jkit.cli;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine.Option;

/** Options shared by commands that may reach the network. */
final class Options {
    @Option(names = "--refresh", description = "Fetch version lists again instead of using the cache.")
    boolean refresh;

    @Option(names = "--offline", description = "Never contact the network; use cached version lists only.")
    boolean offline;

    Context context() {
        Context ctx = Context.create(refresh, offline);
        String typo = misspelled(ctx.env);
        if (typo != null && WARNED.compareAndSet(false, true)) {
            ctx.err.println("warning: " + typo);
        }
        return ctx;
    }

    /**
     * Variables people set for a jkit setting under another name, which jkit would otherwise ignore
     * without a word: the name set, and the one jkit reads.
     */
    private static final Map<String, String> TYPOS = Map.of(
            "JKIT_OFFLINE", "JKIT_NETWORK_OFFLINE");

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** The first of {@link #TYPOS} set in {@code env}, or null. */
    static String typoName(Map<String, String> env) {
        return TYPOS.keySet().stream().filter(env::containsKey).sorted().findFirst().orElse(null);
    }

    /** "JKIT_OFFLINE is set, but jkit reads JKIT_NETWORK_OFFLINE (it ignores JKIT_OFFLINE)", or null. */
    static String misspelled(Map<String, String> env) {
        String name = typoName(env);
        return name == null ? null : name + " is set, but jkit reads " + TYPOS.get(name) + " (it ignores " + name + ")";
    }
}
