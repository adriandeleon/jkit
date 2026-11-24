package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** --help is printed 80 columns wide: a longer line wraps and leaves a word on a line of its own. */
class HelpTextTest {
    private static final int WIDTH = 80;
    /** jkit --help lists each command's first line from column 14: "  completion  ". */
    private static final int SUMMARY = WIDTH - 14;

    @Test
    void everyDescriptionLineFitsTheHelpWidth() {
        List<String> tooLong = new ArrayList<>();
        collect(new CommandLine(new Main()), tooLong);
        assertThat(tooLong).isEmpty();
    }

    private static void collect(CommandLine cli, List<String> tooLong) {
        String name = cli.getCommandSpec().qualifiedName();
        if (cli.getCommand() instanceof CommandLine.HelpCommand) {
            return; // picocli's own text
        }
        // hidden commands (hook-env) aren't listed in jkit --help, so their first line isn't a summary
        boolean listed = cli.getParent() != null && !cli.getCommandSpec().usageMessage().hidden();
        String[] description = cli.getCommandSpec().usageMessage().description();
        for (int i = 0; i < description.length; i++) {
            int limit = i == 0 && listed ? SUMMARY : WIDTH; // the first line is also the summary
            if (description[i].length() > limit) {
                tooLong.add(name + " (" + description[i].length() + " > " + limit + "): " + description[i]);
            }
        }
        for (CommandLine sub : cli.getSubcommands().values()) {
            collect(sub, tooLong);
        }
    }
}
