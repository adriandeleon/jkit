package dev.jkit.platform;

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
                __jkit_g='%2$s'
                for __jkit_t in gradle maven jdk; do
                  case ":$PATH:" in *":$__jkit_g/$__jkit_t/bin:"*) ;; *) PATH="$__jkit_g/$__jkit_t/bin:$PATH" ;; esac
                done
                export PATH
                [ -z "$JAVA_HOME" ] && [ -e "$__jkit_g/jdk" ] && export JAVA_HOME="$__jkit_g/jdk"
                [ -z "$MAVEN_HOME" ] && [ -e "$__jkit_g/maven" ] && export MAVEN_HOME="$__jkit_g/maven"
                [ -z "$GRADLE_HOME" ] && [ -e "$__jkit_g/gradle" ] && export GRADLE_HOME="$__jkit_g/gradle"
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
                """.formatted(shell, g);
    }

    static String fish(Path root) {
        String g = root.resolve("global").toString();
        return """
                # jkit shell hook (fish)
                set -gx JKIT_SHELL fish
                for t in gradle maven jdk
                  contains -- '%1$s'/$t/bin $PATH; or set -gx PATH '%1$s'/$t/bin $PATH
                end
                set -q JAVA_HOME; or test -e '%1$s/jdk'; and set -gx JAVA_HOME '%1$s/jdk'
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
        String g = root.resolve("global").toString();
        return """
                # jkit shell hook (PowerShell)
                $env:JKIT_SHELL = 'pwsh'
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
                # global folders: %s
                """.formatted(g);
    }
}
