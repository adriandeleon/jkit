package dev.jkit.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code HKCU\Environment}, read and written by Windows PowerShell, which every Windows has.
 * Through the registry rather than {@code [Environment]::SetEnvironmentVariable}: that one reads
 * a value expanded and writes it back as a plain string, so a user PATH entry such as
 * {@code %USERPROFILE%\AppData\Local\Microsoft\WindowsApps} would stop following the profile
 * folder. Values travel base64-encoded UTF-8, in both directions: through environment variables
 * there is nothing to quote, and on the way out the console code page can't change a character.
 */
final class WindowsUserEnv implements UserEnv {

    private static final String READ = """
            $k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
            foreach ($n in $env:JKIT_NAMES.Split([char]10)) {
              if (-not $n) { continue }
              $v = if ($k) { $k.GetValue($n, $null, 'DoNotExpandEnvironmentNames') } else { $null }
              if ($null -ne $v) { $n + [char]9 + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$v)) }
            }
            $m = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment')
            $p = if ($m) { [Environment]::ExpandEnvironmentVariables([string]$m.GetValue('Path', '', 'DoNotExpandEnvironmentNames')) } else { '' }
            '*system-path*' + [char]9 + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($p))
            """;

    private static final String WRITE = """
            $ErrorActionPreference = 'Stop'
            $k = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
            foreach ($line in $env:JKIT_SET.Split([char]10)) {
              if (-not $line) { continue }
              $n, $b = $line.Split([char]9)
              if ($b -eq '-') { $k.DeleteValue($n, $false); continue }
              $v = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b))
              $kind = if ($k.GetValue($n) -ne $null) { $k.GetValueKind($n) } elseif ($v.Contains('%')) { 'ExpandString' } else { 'String' }
              $k.SetValue($n, $v, $kind)
            }
            $k.Close()
            # Deleting a value that doesn't exist changes nothing, but .NET then broadcasts
            # WM_SETTINGCHANGE, so Explorer starts programs with the new values
            [Environment]::SetEnvironmentVariable('JKIT_NO_SUCH_VARIABLE', $null, 'User')
            """;

    private String systemPath;

    @Override
    public Map<String, String> read(Collection<String> names) throws IOException {
        String out = run(READ, "JKIT_NAMES", String.join("\n", names));
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : out.split("\r?\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String value = new String(Base64.getDecoder().decode(line.substring(tab + 1).trim()), StandardCharsets.UTF_8);
            String name = line.substring(0, tab);
            if (name.equals("*system-path*")) {
                systemPath = value;
            } else {
                values.put(name, value);
            }
        }
        return values;
    }

    @Override
    public String systemPath() throws IOException {
        if (systemPath == null) {
            read(java.util.List.of());
        }
        return systemPath;
    }

    @Override
    public void write(Map<String, String> values) throws IOException {
        StringBuilder lines = new StringBuilder();
        values.forEach((name, value) -> lines.append(name).append('\t')
                .append(value == null ? "-" : Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .append('\n'));
        run(WRITE, "JKIT_SET", lines.toString());
    }

    /** Runs {@code script} in Windows PowerShell with {@code input} in the variable {@code name}. */
    private static String run(String script, String name, String input) throws IOException {
        // the full path: a bare name is looked up in the current folder first on Windows
        String systemRoot = Objects.requireNonNullElse(System.getenv("SystemRoot"), "C:\\Windows");
        String powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        // -EncodedCommand: no quoting, and the execution policy only covers script files
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true);
        pb.environment().put(name, input);
        Process p = pb.start();
        p.getOutputStream().close();
        try {
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IOException("couldn't " + (name.equals("JKIT_SET") ? "change" : "read")
                        + " the user's environment variables: " + output.trim());
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
