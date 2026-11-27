package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CompletionTest {

    @Test
    void scriptCoversCommandsToolsAndVendors() {
        String script = CompletionCommand.script(new CommandLine(new Main()));
        assertThat(script).contains("complete -F _complete_jkit -o default jkit")
                .contains("search install upgrade use reset list status env exec pin unpin sync doctor remove prune config self-update hook completion copyright help")
                .contains("\"jdk\" \"maven\" \"gradle\" \"sbt\" \"graalvm\"")
                .contains("\"temurin\" \"corretto\" \"zulu\"")
                .contains("--global");
    }

    /** Loading the hook in zsh must not change the user's options or aliases. */
    @Test
    void theScriptLeavesZshOptionsAndAliasesAlone() {
        String script = CompletionCommand.script(new CommandLine(new Main()));
        assertThat(script).doesNotContain("setopt").doesNotContain("alias ")
                .contains("autoload -U +X bashcompinit && bashcompinit")
                // compopt, which only bash has, is called only where it exists
                .doesNotContainPattern("(?m)^\\s*compopt");
        assertThat(script.lines().filter(l -> l.contains("compopt")))
                .allMatch(l -> l.contains("type compopt &>/dev/null && compopt"));
    }

    /** With set -u (nounset), zsh stopped the hook's eval at picocli's unguarded "$BASH_VERSION". */
    @Test
    void theScriptLoadsWithNounset() throws Exception {
        String script = CompletionCommand.script(new CommandLine(new Main()));
        assertThat(script).doesNotContain("\"$BASH_VERSION\"").doesNotContain("\"$ZSH_VERSION\"");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/bash")));
        java.nio.file.Path file = java.nio.file.Files.createTempFile("jkit-completion", ".bash");
        try {
            java.nio.file.Files.writeString(file, script);
            Process p = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-u", "-c",
                    "unset ZSH_VERSION; source \"$0\" && complete -p jkit", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(p.waitFor()).as(out).isZero();
            assertThat(out).contains("_complete_jkit");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
