package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** --help is printed 80 columns wide: a longer line wraps and leaves a word on a line of its own. */
class HelpTextTest {
    private static final int WIDTH = 80;

    @Test
    void everyDescriptionLineFitsTheHelpWidth() {
        List<String> tooLong = new ArrayList<>();
        collect(new CommandLine(new Main()), tooLong);
        assertThat(tooLong).isEmpty();
    }

    private static void collect(CommandLine cli, List<String> tooLong) {
        String name = cli.getCommandSpec().qualifiedName();
        for (String line : cli.getCommandSpec().usageMessage().description()) {
            if (line.length() > WIDTH) {
                tooLong.add(name + " (" + line.length() + "): " + line);
            }
        }
        for (CommandLine sub : cli.getSubcommands().values()) {
            collect(sub, tooLong);
        }
    }
}
