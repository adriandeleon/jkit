# Project files (pin and sync)

Status: implemented, phases 1 to 4 (see the end).

## Goal

A repository records the JDK, GraalVM, Maven, Gradle and sbt it builds with, and every shell
with the jkit hook switches to those versions on entering it. This matches what
`.sdkmanrc` + `sdkman_auto_env` and `.nvmrc` do, and is the main gap with SDKMAN.

```console
$ cd ~/src/payments
jkit: jdk 21.0.9+10 temurin, maven 3.9.11 (from jkit.toml)
$ cd ~
jkit: back to global jdk, maven
```

Not in scope here: `status`, `env`, `doctor`, `init`, auto-installing on `cd`, Windows.

## The file: `jkit.toml`

TOML, because jkit already parses TOML (`config.toml`, tomlj) and it leaves room to grow.
The usual name is a visible `jkit.toml` at the project root, like `mise.toml`, so people can
see the project pins a toolchain. `.jkit.toml` works too for those who want it hidden. If
both are in one folder, `jkit.toml` wins and the other gets a warning.

```toml
# jkit.toml
[tools]
jdk = "21"                                        # newest installed 21, any vendor
maven = "3.9"
gradle = "8.14.3"                                 # exact
graalvm = { version = "25", vendor = "graalvm-ce" }

# the long form, for when vendor or JavaFX matters
# jdk = { version = "21.0.9+10", vendor = "zulu", fx = true }
```

- Keys are tool ids from `Tool.parse`, so the `java` and `mvn` aliases work too.
- `sbt` picks the launcher only. The sbt version a build runs on still comes from its
  `project/build.properties`, which the launcher reads and downloads itself.
- `version` is matched as a prefix with `Version.matches`, the same rule `jkit use jdk@21`
  uses today. `"latest"` or a missing version means newest.
- `vendor` (JDK and GraalVM only) goes through `Vendor.of`, so names and aliases match the CLI.
- `fx` is optional and defaults to false, with the same "plain wins" rule as `use`.
- Unknown keys, sections or tools give a **warning** naming the file and line, not an error,
  so a newer file still works with an older jkit.

### Vendor left out

`jdk = "21"` means "the project needs a 21 and doesn't care about the vendor". This is
the common case, and the file then works on machines with different default vendors.

- **Activating:** prefer an install from the user's default vendor (`jdk.vendor`). If there
  is none, use the newest matching install from any vendor.
- **Syncing:** if nothing matching is installed, install from the user's default vendor.

A team that needs a specific vendor writes it, and then only that vendor matches.

### Exact versions

The project file itself holds the version. A team that wants reproducible builds writes an
exact build (`"21.0.9+10"`), and `jkit pin --exact` writes one for them. There is no
separate lock file: an exact version in `jkit.toml` is enough.

## Other files jkit reads

These are read so existing repositories work without changes and SDKMAN users can switch.
jkit only reads them and never writes them.

| File | Example | Mapping |
|---|---|---|
| `.sdkmanrc` | `java=21.0.5-tem`, `maven=3.9.9`, `gradle=8.10`, `sbt=1.10.7` | `java` → jdk. The suffix maps to a vendor: `tem`→temurin, `zulu`, `amzn`→corretto, `librca`→liberica, `ms`→microsoft, `sapmchn`→sapmachine, `sem`→semeru, `graalce`→graalvm-ce, `graal`→graalvm, `nik`→liberica-nik, `mandrel`, `oracle`, `open`→openjdk, `kona`, `albba`→dragonwell, `jbr`→jetbrains. `.fx` before the suffix (`21.0.5.fx-zulu`) means `fx = true`. SDKMAN ids have no build number, so they match as a prefix. Other candidates (kotlin, …) are skipped with one warning. |
| `.tool-versions` (asdf, mise) | `java temurin-21.0.5+11.0.LTS`, `java 21`, `maven 3.9.9` | `java` → jdk. `vendor-version` splits at the first `-` followed by a digit. asdf/mise names that differ map to jkit's (`adoptopenjdk`→temurin, `graalvm-community`→graalvm-ce, `oracle-graalvm`→graalvm, `semeru-openj9`→semeru), and others pass through as vendor names. A `-javafx` or `-fx` vendor suffix means `fx = true`. A plain number means any vendor, and `latest` means newest. Only the first version on a line is used, not asdf's fallbacks. `system`, `ref:` and `path:` give a warning and are skipped, as do other tools. Known limit: asdf's `zulu-…` ids use Zulu's own numbering, which won't match. |
| `.java-version` | `21` or `17.0.2` | jdk only, vendor left out. `1.8` style is read as `8`. Values jenv uses that aren't versions (`openjdk64-17.0.2`, `system`) give a warning and are skipped. |

A GraalVM `java=` entry in `.sdkmanrc` becomes a GraalVM-based jdk, as it does in SDKMAN.
The existing "GraalVM follows JDK" logic in `ShellEnv` then sets `GRAALVM_HOME` too.

## Finding the file

Start at the current directory and walk up. Below `$HOME`, the search **stops at `$HOME`**,
which is still checked, so `~/jkit.toml` can act as a personal default. Outside `$HOME`
(a CI workspace, `/srv/…`) it goes to the filesystem root. The **first directory that has
any recognised file** is the project. Within that directory:

1. `jkit.toml` (or else `.jkit.toml`) wins, and the other files are ignored.
2. Otherwise `.sdkmanrc`, `.tool-versions` and `.java-version` are all read. Where several
   set the same tool, the earlier one in that list wins.

Files from different directories are never merged. A nested `jkit.toml` replaces the outer
one completely, so you don't need to read two files to know what applies.

`JKIT_PROJECT_FILE=/path/to/jkit.toml` overrides discovery, and an empty value turns project
files off. This is for CI and tests.

## Precedence

From highest to lowest:

1. **Shell choice**: `jkit use` without `--global` in this shell (`JKIT_SHELL_<TOOL>`).
2. **Project**: the file found above.
3. **Global**: `jkit use … --global` (`~/.jkit/global/<tool>`).

This works per tool. A project that pins only `jdk` leaves maven and gradle on their
shell or global choice.

An explicit `jkit use` keeps winning after you `cd` into a project, because the user asked for
it in this shell. When a project's choice is hidden this way, the hook says so once on
entering the project:

```
jkit: jdk 17 zulu is set for this shell, so jkit.toml's jdk 21 doesn't apply (jkit reset jdk)
```

`jkit reset jdk` drops the shell choice and goes back to the project, or to global outside
one. Today it always goes back to global, so this changes `ShellEnv.reset`.

## Shell integration

### State

`ShellEnv` keeps the shell's state in environment variables, so the binary stays stateless.
New variables:

- `JKIT_PROJECT_<TOOL>`: the install home applied from the project, as `JKIT_SHELL_<TOOL>` is
  for `use`.
- `JKIT_PROJECT`: the project file applied, plus its modification time (`path:mtime`). The
  hook passes this back, so the binary can tell nothing changed and print nothing.

`ShellEnv.path` builds `active` as global < project < shell and keeps its rule of only ever
removing PATH entries under `tools/`.

### When it runs

The hook runs a hidden `jkit hook-env` (no network, no index, no lock):

- **bash:** a `PROMPT_COMMAND` function that returns at once unless `$PWD` changed since it
  last ran. It runs only the comparison, not a process, on every prompt.
- **zsh:** `chpwd` plus one run when the hook loads.
- **fish:** `--on-variable PWD` plus one run when the hook loads.
- **pwsh:** later, since that hook is untested.

It runs on directory change and when the hook loads, not on every prompt. Editing `jkit.toml`
takes effect on the next `cd`, `jkit sync` or `jkit pin`, and `pin` and `sync` apply
straight away through the existing `JKIT_ENV_FILE` mechanism.

`hook-env` prints shell code on stdout (`ShellEnv.format`) and short notices on stderr. With
no project and no project state set it prints nothing. **Budget: under 10 ms** on a warm
cache, measured on the native binary. It must skip `Context` setup that reaches the network
or loads indexes, and use only `Store.list` (marker files) for lookups.

### Turning on auto-switch

Auto-switching is **opt-in**, as SDKMAN's `sdkman_auto_env` is. It's on only with
`shell.auto_switch = true` in `config.toml` or `JKIT_AUTO_SWITCH=1`. When it's off, the hook
installs no directory-change function at all, so shells pay nothing. `jkit sync` still
applies the project to the current shell either way. While auto-switch is off, `sync` and
`pin` add a note on how to turn it on.

### Missing installs

Entering a project whose tools aren't installed **does not download anything**: `cd` must
stay fast and work offline. The tool stays on its shell or global choice, and one line goes
to stderr:

```
jkit: jkit.toml wants jdk 21 (zulu), not installed: jkit sync
```

## Commands

### `jkit pin <tool>[@version] [vendor] [--fx] [--exact] [--here] [--json]`

Writes or updates the entry for `<tool>` in the project's `jkit.toml`. If no `jkit.toml`
is found walking up, or `--here` is given, it uses `jkit.toml` in the current directory and
creates it if needed. It edits only that one key and
keeps comments and order. tomlj can't write TOML, so this is a small line-based edit of the
`[tools]` table, and a fresh file is written from a template.

- The version must match an installed version, or one the index knows about, so typos fail
  here rather than on a teammate's machine. With `--offline`, installed versions only.
- `--exact` writes the full version (and vendor) of the install it resolves to.
- With the hook active, it applies to the current shell immediately.
- It refuses when the project file found is a `.sdkmanrc`, `.tool-versions` or `.java-version`, and says to
  create a `jkit.toml` with `jkit pin --here`.

`jkit unpin <tool>` removes the entry.

### `jkit sync [--dry-run] [--refresh|--offline] [--json]`

Reads the project file, installs what's missing (the same code as `install`, so same
checksums, progress and exit codes), then applies the project to the current shell if the
hook is active. `--dry-run` lists what it would install. With no project file: exit 1 and
`no jkit.toml, .sdkmanrc or .java-version here or above`.

For CI, use the hook the same way as on a desktop. `jkit` is a shell function, so
`jkit sync` updates the calling script's environment:

```bash
eval "$(jkit hook bash)"
jkit sync
mvn -B verify
```

### Changes to existing commands

- `list` marks project-selected installs, and `--json` gains `"project": {"file": …,
  "tools": {…}}` next to `"global"`. Update `docs/json.md` and bump `schemaVersion` only if a
  field changes meaning.
- `use` adds a note when a project pins the same tool:
  `overrides jkit.toml's jdk 21 in this shell; jkit reset jdk to go back to it`.
- `reset` goes back to the project, as described in Precedence.
- `remove` warns, but doesn't refuse, when the install is the one the current project picks.

## Code layout

| Module | New |
|---|---|
| `jkit-model` | `PinnedTool` (tool + version + vendor + fx), `ProjectSpec` (files, tool → `PinnedTool`, warnings), `SdkmanIds` and `ToolVersionsIds` (other tools' ids → `PinnedTool`), `Version.looksLikeVersion` |
| `jkit-core` | `ProjectFile`: discovery and parsing of all formats into `ProjectSpec`, plus warnings. `ProjectWriter`: the `[tools]` edit for `pin`. `ShellEnv`: project layer, `JKIT_PROJECT*` state, project-aware `reset` |
| `jkit-platform` | `Hooks`: directory-change hooks for bash, zsh and fish |
| `jkit-cli` | `PinCommand`, `UnpinCommand`, `SyncCommand`, hidden `HookEnvCommand`; project-aware `Resolve.installed` for vendor-less entries |

## Tests

- **Parsing:** each format, aliases, unknown keys (warning), bad TOML (error with file and
  line), every SDKMAN suffix, `.fx-`, and jenv values that aren't versions.
- **Discovery:** nearest directory wins, `jkit.toml` beats the others, no merging,
  `JKIT_PROJECT_FILE` set and empty.
- **`ShellEnv`:** shell > project > global for every tool, leaving a project restores global,
  `reset` falls back to project, GraalVM follows JDK under a project, and PATH never loses
  user entries. These go in the existing `ShellEnvTest`.
- **`pin`:** preserves comments and order, creates a file, `--exact`.
- **Hook:** a bash/zsh/fish script test that `cd`s in and out and checks `JAVA_HOME` and
  `PATH`, plus a timing check for `hook-env` on the native binary.

## Rollout

1. Parsing and discovery (model + core), with tests. No user-visible change.
2. `pin`, `unpin`, `sync` (explicit only): useful on their own, with `sync` applying through
   the existing wrapper.
3. Project layer in `ShellEnv`, `hook-env` and directory-change hooks for bash, zsh and fish.
4. `list`/`use`/`reset`/`remove` changes, `docs/json.md`, README ("In the prototype" and
   "Not yet").

## Decisions

1. **Lock file:** none. An exact version written with `jkit pin --exact` is enough.
2. **`.tool-versions`:** read, alongside `.sdkmanrc` and `.java-version`.
3. **`.jkit.toml`:** accepted. `jkit.toml` wins when both exist.
4. **Auto-switch on `cd`:** opt-in (`shell.auto_switch = true`). Installing on `cd` stays
   off; the hint is enough.
5. **Where the search stops:** at `$HOME` for directories below it, and at the root
   elsewhere.

Status: all four phases are implemented. Phase 1 (parsing and discovery): `ProjectFile`,
`SdkmanIds` and `ToolVersionsIds`, tested in `ProjectFileTest` and `ProjectIdsTest`. Phase 2
(`pin`, `unpin`, `sync`): `PinCommand`, `UnpinCommand`, `SyncCommand` and `ProjectWriter`, tested in
`ProjectWriterTest` and `PinSyncTest`. Phase 3 (the project layer and switching on `cd`):
`ShellEnv`, the hidden `HookEnvCommand` and `Hooks.autoSwitch`, tested in `ShellEnvTest`,
`HookEnvTest` and `HooksTest`. Phase 4: `list`, `use` and `remove`. PowerShell doesn't switch on
`cd` yet.

Phase 2 notes, where it differs from or adds to the text above:

- `pin` without a version pins what this shell uses: the feature release for jdk and graalvm
  (`"21"`), the full version for the others. `--exact` writes the full version and vendor.
- `pin` checks a version that isn't installed against the tool's version list, which `--offline`
  reads from the cache instead of the network.
- `pin` reads every edit back and refuses one that would change anything but that tool's line
  (a `[tools.jdk]` table, a dotted `tools.jdk` key), asking for a hand edit instead.
- `reset <tool>` goes back to the project's install only while the project found from the current
  folder still pins the tool; otherwise it also drops the project state and goes back to global.
  This is how to leave a project's versions until the `cd` hooks land.

Phase 3 and 4 notes, where they differ from or add to the text above:

- Every switch on `cd` is announced, never silent: a `jkit: using ~/src/app/jkit.toml: jdk 21.0.9+10
  temurin, maven 3.9.11` line (or `jkit: left the project in ~/src/app: back to the global
  versions`), then one line per variable set, unset, or PATH entry added or removed, as other
  commands print them, then the notes (pins not installed, a `jkit use` choice that keeps winning).
  The same project again, such as a `cd` into a subfolder, prints nothing.
- `JKIT_PROJECT` is `path:mtime` per file, joined by `;`. `ProjectFile.locate` finds the files
  without reading them, so an unchanged project costs a few file checks. When a pin wasn't
  installed, the value ends in `;missing` and the next `cd` looks again, quietly unless something
  can now be applied.
- A broken project file is reported once (until it changes), and leaves the shell's versions as
  they were.
- `hook-env` never fails: an unexpected error is one `jkit:` line on stderr, and the `cd` goes
  ahead.
- `jkit hook` reads `shell.auto_switch` when the shell starts, so turning it on or off takes a new
  shell. `jkit hook <shell> --install --auto-switch` adds the hook to the shell's startup file and
  sets `auto_switch = true` in `config.toml` (`ConfigWriter`, which edits one line and reads the
  file back), printing both changes. For PowerShell, `jkit hook pwsh` warns that it isn't supported yet.
- `sync`, `pin` and `unpin` also record `JKIT_PROJECT`, so the next `cd` within the project stays
  quiet. While auto-switch is off they add a note on how to turn it on.
- `list` marks installs with the project file's name (`jkit.toml`), and its `--json` has `project`
  (`dir`, `files`, `tools`: tool → install, or `null` when not installed).
- `use` notes when it overrides the project's pin in this shell, and `use --global` when the
  project's pin wins over the new global version in this folder.
- `remove` warns when the project here pins the install, and when this shell's `*_HOME` came from
  the project and now points at the removed folder.
