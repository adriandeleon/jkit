# Project files

A project file records the JDK, GraalVM, Maven, Gradle and sbt a repository builds with.
`jkit sync` installs what it asks for and switches the current shell to it; with auto-switch on,
every shell with the jkit hook switches when you `cd` into the project and back when you leave.

```console
$ jkit pin jdk@21
$ jkit pin maven@3.9
$ cat jkit.toml
# Versions jkit uses in this project: jkit sync installs them.
[tools]
jdk = "21"
maven = "3.9"
$ jkit sync
```

## `jkit.toml`

```toml
[tools]
jdk = "21"                                        # newest installed 21, any vendor
maven = "3.9"
gradle = "8.14.3"                                 # exact
graalvm = { version = "25", vendor = "graalvm-ce" }
# jdk = { version = "21.0.9+10", vendor = "zulu", fx = true }   # the long form
```

- Keys: `jdk` (or `java`), `graalvm`, `maven` (or `mvn`), `gradle`, `sbt`.
- `version` matches as a prefix: `"21"` is any 21, `"21.0.9+10"` one build. Left out or `"latest"`
  means newest. Write an exact build (or run `jkit pin --exact`) for reproducible builds; there is
  no separate lock file.
- `vendor` (JDK and GraalVM only) uses the same names as the command line. Left out, jkit prefers
  your default vendor and otherwise takes the newest matching install from any vendor, so the file
  works on machines with different defaults.
- `fx = true` asks for a JavaFX build.
- `sbt` picks the launcher only: the sbt version a build runs on still comes from its
  `project/build.properties`.
- Unknown keys or tools give a warning naming the file and line, not an error, so a file written
  for a newer jkit still works. Versions and vendors must be plain text (letters, digits, `. + _ -`);
  anything else is skipped with a warning.

`.jkit.toml` works too, for those who want it hidden; `jkit.toml` wins when both are there.

## Other files jkit reads

jkit reads these so existing repositories work unchanged, and never writes them. Several in one
folder are read together, the earlier one in this list winning per tool.

| File | Example | Notes |
|---|---|---|
| `.sdkmanrc` | `java=21.0.5-tem`, `maven=3.9.9` | SDKMAN's vendor suffixes map to jkit's vendors (`tem` temurin, `zulu`, `amzn` corretto, `librca` liberica, `ms` microsoft, `sapmchn` sapmachine, `sem` semeru, `graalce` graalvm-ce, `graal` graalvm, `nik` liberica-nik, `mandrel`, `oracle`, `open` openjdk, `kona`, `albba` dragonwell, `jbr` jetbrains, `trava` trava, `bsg` bisheng). `21.0.5.fx-zulu` means a JavaFX build. Other candidates are skipped with one warning. |
| `.tool-versions` (asdf, mise) | `java temurin-21.0.5+11.0.LTS`, `maven 3.9.9` | asdf names map to jkit's (`adoptopenjdk` temurin, `graalvm-community` graalvm-ce, …). Only the first version on a line counts. `system`, `ref:` and `path:` are skipped. asdf's Zulu ids use Zulu's own numbering and don't match. |
| `.java-version` | `21`, `17.0.2` | jdk only, any vendor; `1.8` reads as 8. |

## Which file applies

jkit starts in the current folder and walks up. The first folder with any of these files is the
project; files from different folders are never merged, so a nested `jkit.toml` replaces the outer
one completely.

- Below your home folder the search stops at `$HOME` (still checked, so `~/jkit.toml` can be a
  personal default). Elsewhere, such as a CI workspace, it goes up to `/`.
- It stops at a folder that belongs to another user (other than root) or that every user can write
  to, such as `/tmp`: someone else's `/tmp/jkit.toml` must not change your versions. A project
  file that is itself world-writable, or owned by another user, is ignored the same way.
  `jkit status`, `jkit sync` and `jkit doctor` say when they ignored one, and why. This often
  affects WSL's `/mnt/c`, SMB shares and Docker bind mounts, where every file looks world-writable;
  fix the mount options or permissions, or name the file with `JKIT_PROJECT_FILE`.
- `JKIT_PROJECT_FILE=/path/to/jkit.toml` names the file directly and skips those checks (it's your
  own choice); a relative path is looked up from each folder, so the checks still apply to it. Set
  but empty, it turns project files off.
- Project files over 256 KiB are refused, and `jkit pin` won't write through a `jkit.toml` that is
  a symbolic link.

## Which version wins

Per tool, from highest to lowest:

1. `jkit use` in this shell (without `--global`).
2. The project file.
3. The global default (`jkit use … --global`).

A project that pins only `jdk` leaves Maven and Gradle on their shell or global choice. A `jkit use`
keeps winning after you `cd` into a project, and the hook says so; `jkit reset jdk` goes back to the
project's version.

## Switching on `cd`

Off by default. Turn it on with:

```bash
jkit hook --install --auto-switch
```

That sets `auto_switch = true` under `[shell]` in `~/.jkit/config.toml` (`JKIT_AUTO_SWITCH=1` works
too). bash, zsh and fish then switch when you enter a project and go back to the global versions
when you leave, printing every variable they change. PowerShell doesn't switch on `cd` yet.

- Entering a project never downloads anything: a missing version stays on its shell or global
  choice, with a note to run `jkit sync`.
- bash switches when the next prompt is drawn, so `cd app && java -version` on one line still runs
  the old JDK. In scripts and one-liners, use `jkit exec -- java -version` instead.
- Editing the project file takes effect on the next `cd`, `jkit sync` or `jkit pin`.
- A change to `auto_switch` in `config.toml` applies to new shells. To stop switching in a shell
  that is already open, `export JKIT_AUTO_SWITCH=0` there (`set -gx JKIT_AUTO_SWITCH 0` in fish).
- A `JAVA_HOME` (or other `*_HOME`) you set yourself, outside jkit, is kept in `JKIT_USER_JDK`
  (`JKIT_USER_MAVEN`, ...) when a project or `jkit use` first replaces it. It comes back, instead of
  the global version, once neither applies any more (you leave the project, `jkit reset jdk`), and
  jkit says so each time.

## Commands

- `jkit pin <tool>[@version] [vendor] [--exact] [--here]` writes or updates one line of the nearest
  `jkit.toml` (or creates one here with `--here`), keeping comments and order. The version must be
  installed or downloadable. It won't edit `.sdkmanrc`, `.tool-versions` or `.java-version`.
- `jkit unpin <tool>` removes that line.
- `jkit sync [--dry-run]` installs what the project asks for and switches this shell to it. A
  download with no published checksum is refused unless you add `--allow-unverified`.

In CI, either load the hook and sync:

```bash
eval "$(jkit hook bash)"
jkit sync
mvn -B verify
```

or skip the hook: `jkit sync` then `jkit exec -- mvn -B verify` (see [agents.md](agents.md)).
