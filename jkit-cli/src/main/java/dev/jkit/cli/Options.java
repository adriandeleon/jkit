package dev.jkit.cli;

import picocli.CommandLine.Option;

/** Options shared by commands that may reach the network. */
final class Options {
    @Option(names = "--refresh", description = "Fetch version lists again instead of using the cache.")
    boolean refresh;

    @Option(names = "--offline", description = "Never contact the network; use cached version lists only.")
    boolean offline;

    Context context() {
        return Context.create(refresh, offline);
    }
}
