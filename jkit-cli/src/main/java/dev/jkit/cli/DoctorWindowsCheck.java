package dev.jkit.cli;

import dev.jkit.model.Tool;
import dev.jkit.platform.UserEnv;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Doctor's check of the Windows user environment, which programs outside PowerShell get. */
final class DoctorWindowsCheck {
    private DoctorWindowsCheck() { }

    /**
     * Windows: whether the user's own environment gives programs started outside a hooked
     * PowerShell (cmd.exe, IDEs) jkit's global versions; and what in the system PATH beats them.
     */
    static void userEnv(DoctorReport r) throws IOException {
        UserEnv env = r.ctx.os.userEnv();
        if (env == null) {
            return; // macOS, Linux: the shell's startup file is the place, which hook checks
        }
        Map<String, String> current = env.read(UserEnvSetup.names());
        List<String> set = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Tool tool : UserEnvSetup.TOOLS) {
            Path link = r.ctx.home.global(tool).toAbsolutePath().normalize();
            if (!Files.exists(link)) {
                continue;
            }
            (UserEnvSetup.same(current.get(tool.homeVariable()), link.toString()) ? set : missing).add(tool.homeVariable());
        }
        if (set.isEmpty() && missing.isEmpty()) {
            return; // no global versions: nothing to give other programs
        }
        if (set.isEmpty()) {
            r.info("programs started outside a hooked PowerShell (cmd.exe, IDEs) don't get jkit's global versions; "
                    + "for them too: jkit env --install");
        } else if (!missing.isEmpty()) {
            r.warn("your user environment has " + String.join(", ", set) + " from jkit but not "
                    + String.join(", ", missing) + ", so programs outside PowerShell miss those global versions",
                    "jkit env --install");
        } else {
            r.ok("user environment: " + String.join(", ", set) + " name jkit's global versions, for programs outside PowerShell");
        }
        for (String entry : UserEnvSetup.shadowing(env.systemPath())) {
            r.warn("the system PATH has " + entry + ", which comes before the user PATH and wins over jkit's "
                    + "global versions outside a hooked PowerShell", "remove it from the system PATH (needs admin)");
        }
    }
}
