package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CompletionTest {

    @Test
    void scriptCoversCommandsToolsAndVendors() {
        String script = CompletionCommand.script(new CommandLine(new Main()));
        assertThat(script).contains("complete -F _complete_jkit -o default jkit")
                .contains("search install upgrade use reset list status env exec doctor remove hook completion copyright help")
                .contains("\"jdk\" \"maven\" \"gradle\" \"sbt\" \"graalvm\"")
                .contains("\"temurin\" \"corretto\" \"zulu\"")
                .contains("--global");
    }
}
