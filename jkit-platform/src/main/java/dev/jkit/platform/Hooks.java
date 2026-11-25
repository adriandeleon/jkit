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

    /**
     * How the hook calls jkit: {@code __jkit_run}, which runs the binary at its absolute path when
     * known, so a {@code bin/jkit} in a JDK or build tool that a project puts first on PATH is never
     * what runs on {@code cd}; else, or once that path is gone (a package manager moved it), a PATH
     * lookup.
     */
    static String self(String shell, String self) {
        return self == null ? "''" : ShellQuote.quote(shell, self);
    }

    static String posix(String shell, Path root) {
        return posix(shell, root, null);
    }

    static String posix(String shell, Path root, String self) {
        String g = root.resolve("global").toString();
        return """
                # jkit shell hook (%1$s)
                export JKIT_SHELL=%1$s
                __jkit_g=%2$s
                for __jkit_t in sbt gradle maven jdk; do
                  case ":$PATH:" in *":$__jkit_g/$__jkit_t/bin:"*) ;; *) PATH="$__jkit_g/$__jkit_t/bin:$PATH" ;; esac
                done
                export PATH
                [ -z "${JAVA_HOME-}" ] && [ -e "$__jkit_g/jdk" ] && export JAVA_HOME="$__jkit_g/jdk"
                [ -z "${MAVEN_HOME-}" ] && [ -e "$__jkit_g/maven" ] && export MAVEN_HOME="$__jkit_g/maven"
                [ -z "${GRADLE_HOME-}" ] && [ -e "$__jkit_g/gradle" ] && export GRADLE_HOME="$__jkit_g/gradle"
                [ -z "${SBT_HOME-}" ] && [ -e "$__jkit_g/sbt" ] && export SBT_HOME="$__jkit_g/sbt"
                [ -z "${GRAALVM_HOME-}" ] && [ -e "$__jkit_g/graalvm" ] && export GRAALVM_HOME="$__jkit_g/graalvm"
                unset __jkit_t __jkit_g
                __jkit_bin=%3$s
                __jkit_root=%4$s
                __jkit_run() {
                  if [ -z "$__jkit_bin" ] || [ ! -x "$__jkit_bin" ]; then
                    # gone (a package manager moved it), or unknown: jkit on PATH, never one in a tool's bin;
                    # (pattern) because bash 3.2 (macOS) ends $( ) at a case pattern's lone )
                    __jkit_bin=$(printf '%%s\n' "$PATH" | tr ':' '\n' | while IFS= read -r __jkit_d; do
                      case "$__jkit_d/" in ("$__jkit_root"/*) ;; (*) if [ -x "$__jkit_d/jkit" ]; then printf '%%s\n' "$__jkit_d/jkit"; break; fi ;; esac
                    done)
                  fi
                  if [ -n "$__jkit_bin" ]; then command "$__jkit_bin" "$@"; else echo "jkit: not found on PATH" >&2; return 127; fi
                }
                jkit() {
                  local __jkit_env __jkit_rc
                  __jkit_env="$(mktemp "${TMPDIR:-/tmp}/jkit-env.XXXXXX")" || return 1
                  JKIT_ENV_FILE="$__jkit_env" __jkit_run "$@"
                  __jkit_rc=$?
                  [ -s "$__jkit_env" ] && . "$__jkit_env"
                  rm -f "$__jkit_env"
                  return $__jkit_rc
                }
                """.formatted(shell, ShellQuote.quote(shell, g), self(shell, self),
                ShellQuote.quote(shell, root.toAbsolutePath().normalize().toString()));
    }

    /**
     * The part of the hook that switches to a project's versions on {@code cd}
     * ({@code shell.auto_switch}): runs {@code jkit hook-env} when the directory changes and once
     * when the hook loads. Null for shells without it (PowerShell, for now).
     */
    static String autoSwitch(String shell) {
        return autoSwitch(shell, null);
    }

    static String autoSwitch(String shell, String self) {
        String hook = switch (shell) {
            case "bash" -> """
                    # switch to a project's versions on cd (shell.auto_switch)
                    __jkit_hook_env() {
                      local __jkit_rc=$?
                      if [ "$PWD" != "${__jkit_pwd-}" ]; then
                        __jkit_pwd=$PWD
                        eval "$(__jkit_run hook-env bash)"
                      fi
                      return $__jkit_rc
                    }
                    case ";${PROMPT_COMMAND-};" in
                      *";__jkit_hook_env;"*) ;;
                      *) PROMPT_COMMAND="__jkit_hook_env${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
                    esac
                    __jkit_hook_env
                    """;
            case "zsh" -> """
                    # switch to a project's versions on cd (shell.auto_switch)
                    __jkit_hook_env() { eval "$(__jkit_run hook-env zsh)"; }
                    autoload -Uz add-zsh-hook
                    add-zsh-hook chpwd __jkit_hook_env
                    __jkit_hook_env
                    """;
            case "fish" -> """
                    # switch to a project's versions on cd (shell.auto_switch); interactive shells only,
                    # since fish scripts that source the hook cd too
                    if status is-interactive
                      function __jkit_hook_env --on-variable PWD
                        __jkit_run hook-env fish | source
                      end
                      __jkit_hook_env
                    end
                    """;
            default -> null;
        };
        return hook;
    }

    static String fish(Path root) {
        return fish(root, null);
    }

    static String fish(Path root, String self) {
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
                set -g __jkit_bin %2$s
                set -g __jkit_root %3$s
                function __jkit_run
                  if test -z "$__jkit_bin"; or not test -x "$__jkit_bin"
                    # gone (a package manager moved it), or unknown: jkit on PATH, never one in a tool's bin
                    set -g __jkit_bin ''
                    for d in $PATH
                      if test (string sub -l (string length -- "$__jkit_root/") -- "$d/") != "$__jkit_root/"; and test -x $d/jkit
                        set -g __jkit_bin $d/jkit
                        break
                      end
                    end
                  end
                  if test -n "$__jkit_bin"
                    command $__jkit_bin $argv
                  else
                    echo "jkit: not found on PATH" >&2
                    return 127
                  end
                end
                function jkit
                  set -l envfile (mktemp)
                  set -lx JKIT_ENV_FILE $envfile
                  __jkit_run $argv
                  set -l rc $status
                  test -s $envfile; and source $envfile
                  rm -f $envfile
                  return $rc
                end
                """.formatted(g, self("fish", self), ShellQuote.quote("fish", root.toAbsolutePath().normalize().toString()));
    }

    static String powershell(Path root) {
        return powershell(root, null);
    }

    static String powershell(Path root, String self) {
        String jkit = self("pwsh", self);
        String g = ShellQuote.quote("pwsh", root.resolve("global").toString());
        return """
                # jkit shell hook (PowerShell)
                $env:JKIT_SHELL = 'pwsh'
                & {
                  # a script block of its own: the profile's $t, $bin, $dir... stay as they were
                  $g = %1$s
                  $sep = [IO.Path]::PathSeparator
                  foreach ($t in 'sbt', 'gradle', 'maven', 'jdk') {
                    $bin = Join-Path (Join-Path $g $t) 'bin'
                    if (($env:PATH -split [regex]::Escape($sep)) -notcontains $bin) { $env:PATH = $bin + $sep + $env:PATH }
                  }
                  foreach ($pair in @(@('JAVA_HOME', 'jdk'), @('MAVEN_HOME', 'maven'), @('GRADLE_HOME', 'gradle'), @('SBT_HOME', 'sbt'), @('GRAALVM_HOME', 'graalvm'))) {
                    $dir = Join-Path $g $pair[1]
                    if (-not [Environment]::GetEnvironmentVariable($pair[0]) -and (Test-Path $dir)) {
                      Set-Item -Path ("Env:" + $pair[0]) -Value $dir
                    }
                  }
                }
                $global:__jkit_bin = %2$s
                $global:__jkit_root = %3$s
                function jkit {
                  # PowerShell drops the first bare -- from a function's arguments, and jkit exec
                  # needs it: put it back where the command line has it. Only when every word
                  # before it is one argument; otherwise the arguments go on as they came.
                  $a = @($args)
                  $line = $MyInvocation.Line
                  $at = $MyInvocation.OffsetInLine
                  if ($line -and $line.Contains('--')) {
                    $call = [Management.Automation.Language.Parser]::ParseInput($line, [ref]$null, [ref]$null).Find({
                      param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.Extent.StartLineNumber -eq 1 -and $n.Extent.StartColumnNumber -eq $at
                    }, $true)
                    if ($call) {
                      $i = 0
                      foreach ($w in @($call.CommandElements | Select-Object -Skip 1)) {
                        if ($w -is [Management.Automation.Language.CommandParameterAst] -and $w.Extent.Text -eq '--') {
                          if ($i -le $a.Count) {
                            $l = New-Object Collections.Generic.List[object]
                            foreach ($x in $a) { $l.Add($x) }
                            $l.Insert($i, '--')
                            $a = $l.ToArray()
                          }
                          break
                        }
                        $one = ($w -is [Management.Automation.Language.StringConstantExpressionAst]) -or
                          ($w -is [Management.Automation.Language.ExpandableStringExpressionAst]) -or
                          ($w -is [Management.Automation.Language.VariableExpressionAst] -and -not $w.Splatted) -or
                          ($w -is [Management.Automation.Language.CommandParameterAst] -and -not $w.Argument)
                        if (-not $one) { break }
                        $i++
                      }
                    }
                  }
                  $envFile = Join-Path ([IO.Path]::GetTempPath()) ("jkit-env-" + [guid]::NewGuid() + ".ps1")
                  $env:JKIT_ENV_FILE = $envFile
                  try {
                    if (-not ($global:__jkit_bin -and (Test-Path -LiteralPath $global:__jkit_bin))) {
                      # gone (a package manager moved it), or unknown: jkit on PATH, never one in a tool's bin
                      $root = $global:__jkit_root.TrimEnd('\\', '/') + [IO.Path]::DirectorySeparatorChar
                      $global:__jkit_bin = Get-Command jkit -CommandType Application -All -ErrorAction SilentlyContinue |
                        Where-Object { -not $_.Source.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) } |
                        Select-Object -First 1 -ExpandProperty Source
                    }
                    if ($global:__jkit_bin) {
                      & $global:__jkit_bin @a
                      $rc = $LASTEXITCODE
                    } else {
                      [Console]::Error.WriteLine('jkit: not found on PATH')
                      $rc = 127
                    }
                  } finally {
                    # also after Ctrl-C: later jkit runs outside this function must not write to it
                    Remove-Item Env:JKIT_ENV_FILE -ErrorAction SilentlyContinue
                  }
                  # a script block, not the file: the execution policy (Restricted by default in
                  # Windows PowerShell) blocks dot-sourcing a .ps1, and jkit would have reported
                  # changes this shell never got
                  if ((Test-Path $envFile) -and (Get-Item $envFile).Length -gt 0) {
                    . ([scriptblock]::Create([IO.File]::ReadAllText($envFile)))
                  }
                  Remove-Item $envFile -ErrorAction SilentlyContinue
                  $global:LASTEXITCODE = $rc
                }
                """.formatted(g, jkit, ShellQuote.quote("pwsh", root.toAbsolutePath().normalize().toString()));
    }
}
