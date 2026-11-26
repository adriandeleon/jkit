# When the version isn't the one you expected

Three read-only commands answer it. None of them change anything, and none use the network unless
asked.

| Command | Answers |
|---|---|
| `jkit status` | Which JDK, Maven, Gradle, sbt and GraalVM are active here, and why |
| `jkit doctor` | Whether anything in your setup is broken or fighting jkit, with a fix for each |
| `jkit env` | The environment a new process here should get (for CI, IDEs, Docker) |

## `jkit status`

One line per tool: what this shell has and where it comes from (`shell`, `project`, `global`,
`outside` when a `*_HOME` was set outside jkit, or `not set`). A second, indented line appears when
that isn't what jkit would pick, with the command that fixes it:

```console
$ jkit status
jdk      17.0.13 zulu        shell
         jkit.toml wants jdk 21; this shell chose another with jkit use, to follow it: jkit reset jdk
maven    3.9.11              global
```

## `jkit doctor`

Runs a fixed list of checks and prints `ok`, `warn` or `fail` for each, with a `fix:` line under
anything that isn't ok. It exits 1 when something failed, so it can gate a CI step. `--network` also
checks that foojay, services.gradle.org, Maven Central and GitHub can be reached.

It checks: the shell hook, more than one `jkit` on PATH, `JAVA_HOME` and friends set outside jkit,
whether `java`, `mvn`, `gradle` and `sbt` on PATH are jkit's, broken global links, leftovers from
interrupted installs, `config.toml`, the project file's warnings, the age of the version cache, and
whether the active JDK actually runs.

The usual culprits:

- **`JAVA_HOME` set in your shell profile** (`~/.bashrc`, `~/.profile`, `/etc/environment`): it wins
  over jkit's global version, for `java` on PATH too (the hook puts `$JAVA_HOME/bin` first instead
  of jkit's). Remove the export.
- **The hook loads before something else changes PATH** (SDKMAN, a distro's `java` alternatives):
  move the jkit line to the end of the startup file.
- **No hook at all**: `jkit hook --install`, then open a new shell.

## `jkit env`

Prints the environment for a new process in this folder: the global versions, overridden by the
project file. Shell choices don't count, since the output is for a new process.

```bash
eval "$(jkit env)"                 # CI, Docker, scripts without the hook
jkit env --json                    # for an IDE: variables and the folders to put first on PATH
jkit env --no-project              # ignore project files
jkit env --shell fish | source
```

A project pin that isn't installed is an error (exit 3), so a CI step never runs with half an
environment. On failure jkit env also prints `false` on stdout, so `eval "$(jkit env)"` fails too
and a `set -e` script stops there.
