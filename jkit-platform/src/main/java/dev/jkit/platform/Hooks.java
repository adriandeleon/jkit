package dev.jkit.platform;

import dev.jkit.model.ShellQuote;
import java.nio.file.Path;

/**
 * Shell hook scripts. Each adds the global bin folders to PATH, sets the global *_HOME variables
 * when unset, and defines a {@code jkit} function that sources the environment changes the binary
 * writes to $JKIT_ENV_FILE.
 */
final class Hooks {
    private Hooks() { }

    static String posix(String shell, Path root) {
        String g = root.resolve("global").toString();
        return """
                # jkit shell hook (%1$s)
                export JKIT_SHELL=%1$s
                __jkit_g=%2$s
                for __jkit_t in sbt gradle maven jdk; do
                  case ":$PATH:" in *":$__jkit_g/$__jkit_t/bin:"*) ;; *) PATH="$__jkit_g/$__jkit_t/bin:$PATH" ;; esac
                done
                export PATH
                [ -z "$JAVA_HOME" ] && [ -e "$__jkit_g/jdk" ] && export JAVA_HOME="$__jkit_g/jdk"
                [ -z "$MAVEN_HOME" ] && [ -e "$__jkit_g/maven" ] && export MAVEN_HOME="$__jkit_g/maven"
                [ -z "$GRADLE_HOME" ] && [ -e "$__jkit_g/gradle" ] && export GRADLE_HOME="$__jkit_g/gradle"
                [ -z "$SBT_HOME" ] && [ -e "$__jkit_g/sbt" ] && export SBT_HOME="$__jkit_g/sbt"
                [ -z "$GRAALVM_HOME" ] && [ -e "$__jkit_g/graalvm" ] && export GRAALVM_HOME="$__jkit_g/graalvm"
                unset __jkit_t __jkit_g
                jkit() {
                  local __jkit_env __jkit_rc
                  __jkit_env="$(mktemp "${TMPDIR:-/tmp}/jkit-env.XXXXXX")" || return 1
                  JKIT_ENV_FILE="$__jkit_env" command jkit "$@"
                  __jkit_rc=$?
                  [ -s "$__jkit_env" ] && . "$__jkit_env"
                  rm -f "$__jkit_env"
                  return $__jkit_rc
                }
                """.formatted(shell, ShellQuote.quote(shell, g));
    }

    static String fish(Path root) {
        String g = ShellQuote.quote("fish", root.resolve("global").toString());
        return """
                # jkit shell hook (fish)
                set -gx JKIT_SHELL fish
                set -l __jkit_g %1$s
                for t in sbt gradle maven jdk
                  contains -- $__jkit_g/$t/bin $PATH; or set -gx PATH $__jkit_g/$t/bin $PATH
                end
                if not set -q JAVA_HOME; and test -e $__jkit_g/jdk; set -gx JAVA_HOME $__jkit_g/jdk; end
                if not set -q MAVEN_HOME; and test -e $__jkit_g/maven; set -gx MAVEN_HOME $__jkit_g/maven; end
                if not set -q GRADLE_HOME; and test -e $__jkit_g/gradle; set -gx GRADLE_HOME $__jkit_g/gradle; end
                if not set -q SBT_HOME; and test -e $__jkit_g/sbt; set -gx SBT_HOME $__jkit_g/sbt; end
                if not set -q GRAALVM_HOME; and test -e $__jkit_g/graalvm; set -gx GRAALVM_HOME $__jkit_g/graalvm; end
                set -e __jkit_g
                function jkit
                  set -l envfile (mktemp)
                  env JKIT_ENV_FILE=$envfile jkit $argv
                  set -l rc $status
                  test -s $envfile; and source $envfile
                  rm -f $envfile
                  return $rc
                end
                """.formatted(g);
    }

    static String powershell(Path root) {
        String g = ShellQuote.quote("pwsh", root.resolve("global").toString());
        return """
                # jkit shell hook (PowerShell)
                $env:JKIT_SHELL = 'pwsh'
                $__jkitG = %1$s
                $__jkitSep = [IO.Path]::PathSeparator
                foreach ($t in 'sbt', 'gradle', 'maven', 'jdk') {
                  $bin = Join-Path (Join-Path $__jkitG $t) 'bin'
                  if (($env:PATH -split [regex]::Escape($__jkitSep)) -notcontains $bin) { $env:PATH = $bin + $__jkitSep + $env:PATH }
                }
                foreach ($pair in @(@('JAVA_HOME', 'jdk'), @('MAVEN_HOME', 'maven'), @('GRADLE_HOME', 'gradle'), @('SBT_HOME', 'sbt'), @('GRAALVM_HOME', 'graalvm'))) {
                  $dir = Join-Path $__jkitG $pair[1]
                  if (-not [Environment]::GetEnvironmentVariable($pair[0]) -and (Test-Path $dir)) {
                    Set-Item -Path ("Env:" + $pair[0]) -Value $dir
                  }
                }
                Remove-Variable __jkitG, __jkitSep, t, bin, pair, dir -ErrorAction SilentlyContinue
                function jkit {
                  # dot-sourcing needs a .ps1 extension
                  $envFile = Join-Path ([IO.Path]::GetTempPath()) ("jkit-env-" + [guid]::NewGuid() + ".ps1")
                  $env:JKIT_ENV_FILE = $envFile
                  & (Get-Command jkit -CommandType Application | Select-Object -First 1) @args
                  $rc = $LASTEXITCODE
                  Remove-Item Env:JKIT_ENV_FILE
                  if ((Test-Path $envFile) -and (Get-Item $envFile).Length -gt 0) { . $envFile }
                  Remove-Item $envFile -ErrorAction SilentlyContinue
                  $global:LASTEXITCODE = $rc
                }
                """.formatted(g);
    }
}
