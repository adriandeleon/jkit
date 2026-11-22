# Diagnostics: status, env, doctor

Status: implemented (`status`, `env`, `doctor`). Builds on project files (docs/project-files.md);
phase 1 of that (reading `jkit.toml` and friends) is all these commands need.

## Goal

Three read-only commands that answer the questions people ask when Java "isn't the
version I expected":

| Command | Question | Main audience |
|---|---|---|
| `jkit status` | Which JDK/Maven/Gradle/GraalVM is active here, and why? | people, daily |
| `jkit env` | What environment should a process here get? | CI, IDEs, Docker, scripts without the hook |
| `jkit doctor` | Is anything in my setup broken or fighting jkit? | people, when stuck; bug reports |

None of them change anything, and none use the network by default.

## Shared core: where each tool comes from

All three need the same answer per tool, so it lives in `jkit-core` as one function:

```java
record ToolState(
    Tool tool,
    Path shellChoice,          // JKIT_SHELL_<TOOL>: jkit use in this shell, or null
    PinnedTool projectPin,     // from ProjectFile.find, or null
    Installed projectInstall,  // the install the pin resolves to, or null when none matches
    Path global,               // where ~/.jkit/global/<tool> points, or null
    boolean globalDangling,    // the link exists but its target is gone
    String current,            // the tool's *_HOME in this process's environment, or null
    Source wanted,             // SHELL > PROJECT > GLOBAL > NONE: what jkit would pick
    Path wantedHome) { }

enum Source { SHELL, PROJECT, GLOBAL, NONE }

static List<ToolState> ToolStates.read(Map<String,String> env, Path cwd, JkitHome home,
                                       Store store, Config config)
```

Resolving a pin uses the rule from the project-files design: an explicit vendor must
match; with none, prefer the user's default vendor, then the newest match from any vendor;
plain builds beat JavaFX unless `fx = true`. Phase 2 (`sync`) and phase 3 (`hook-env`)
reuse this function, so it is written once.

`current` versus `wantedHome` tells status and doctor whether the shell matches what
jkit would choose. Until auto-switch (project phase 3) lands, a project pin shows as
"wanted, not applied", which is exactly the hint people need.

## `jkit status [--json]`

Per tool, one line: what this shell has, where it comes from, and anything odd.

```console
$ jkit status
jdk      21.0.9+10 temurin   project   ~/src/payments/jkit.toml (jdk = "21")
maven    3.9.11              global
gradle   -                   not set   (8.14.3 installed: jkit use gradle --global)
graalvm  25.0.1 graalvm-ce   global
```

Rules for the source column:

- `shell`: `jkit use` in this shell (`JKIT_SHELL_<TOOL>`).
- `project`: the pin applies and this shell has it.
- `global`: the global link, with no shell choice or project pin.
- `outside`: `JAVA_HOME` (etc.) points somewhere jkit didn't put it. Shown with the path.
- `not set`: nothing.

Mismatches get a second, indented line with the command that fixes it:

```
jdk      17.0.13 zulu        shell
         jkit.toml wants jdk 21 (21.0.9+10 temurin); this shell chose 17 with jkit use: jkit reset jdk
maven    3.9.11              global
         jkit.toml wants maven 4.0 (not installed): jkit sync
```

Then one footer line naming the project file and its warnings count, if any
(`2 warnings in jkit.toml: jkit doctor`). Exit code is always 0: status reports, doctor
judges.

`--json`: `{schemaVersion, project: {files, dir, warnings} | null, tools: [{tool, source,
current, version, vendor, fx, pin, wanted: {source, home}, hint}]}`, documented in
docs/json.md.

## `jkit env [--shell bash|zsh|fish|pwsh] [--global] [--dir <path>] [--json]`

Prints the environment jkit would give a fresh process in the current directory (or
`--dir`): global links, overlaid by the project's pins. Shell choices are ignored, since
the output is for a new process, not this shell.

```console
$ jkit env
export JAVA_HOME='/home/me/.jkit/tools/jdk/21.0.9+10-temurin'
export MAVEN_HOME='/home/me/.jkit/global/maven'
export PATH='/home/me/.jkit/tools/jdk/21.0.9+10-temurin/bin:/home/me/.jkit/global/maven/bin:/usr/bin:…'
```

- For CI: `eval "$(jkit env)"`, with no hook needed. Pairs with `jkit sync` (project phase 2).
- For IDEs: `--json` gives `{schemaVersion, dir, project, variables: {JAVA_HOME: …},
  pathPrepend: [...]}`, so an editor can set a run configuration without parsing shell.
- `--global` ignores project files. `--dir` lets an IDE ask about a project it has open.
- PATH: the chosen tools' bin folders first, then the existing PATH minus entries under
  `~/.jkit/tools`, using the same rule as `ShellEnv.path`. Global bin folders the hook added
  stay, after the chosen ones, so they can't win.
- Format: bash by default; `--shell` (or `JKIT_SHELL`) picks fish or pwsh syntax via
  `ShellEnv.format`.
- **A pinned tool that isn't installed is an error**: exit 3 (`NOT_INSTALLED`), nothing on
  stdout, `jkit sync` as the hint. A CI step that `eval`s half an environment and builds with
  the wrong JDK is worse than one that stops.

`jkit env` is not SDKMAN's `sdk env` (which switches the current shell). That job is
`jkit sync` here, and the help text says so.

## `jkit doctor [--network] [--json]`

Runs a fixed list of checks and prints one line each: `ok`, `warn` or `fail`, with a `fix:`
line under anything that isn't ok. Exit 0 when nothing failed, 1 otherwise, so it can gate
a CI step.

```console
$ jkit doctor
ok    jkit 0.2.1, linux-x64, home ~/.jkit
ok    shell hook loaded (bash)
warn  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 is set outside jkit and wins over the global jdk
      fix: remove the export from your shell profile (~/.bashrc, ~/.profile)
warn  'java' on PATH is /usr/bin/java, not $JAVA_HOME/bin/java
      fix: load the jkit hook after other PATH changes in ~/.bashrc
fail  global maven → ~/.jkit/tools/maven/3.9.6, which no longer exists
      fix: jkit use maven@3.9 --global
ok    active jdk runs: openjdk 21.0.9 (x64)
2 warnings, 1 problem
```

| Id | Checks | Level |
|---|---|---|
| `home` | jkit version, platform, `JKIT_HOME` exists and is writable | fail if not writable |
| `hook` | `JKIT_SHELL` set (hook loaded); wrapper active (`JKIT_ENV_FILE` set when run through it) | warn |
| `binary` | more than one `jkit` on PATH, or the hook's `jkit` isn't the first one | warn |
| `outside-vars` | `JAVA_HOME`, `MAVEN_HOME`, `GRADLE_HOME`, `GRAALVM_HOME` point outside `~/.jkit` while jkit has a choice for that tool (same rule as `Changes.globalEffect`) | warn |
| `path-order` | `java`, `mvn`, `gradle` on PATH resolve to the active jkit tool's `bin` | warn |
| `links` | global links and `JKIT_SHELL_*` point at installs that still exist | fail |
| `store` | install folders without a marker (interrupted install); leftovers in `~/.jkit/tmp` with their size | warn |
| `config` | `config.toml` parses; default vendors are known (cached index only) | fail / warn |
| `project` | the project file's warnings; pins with no matching install; auto-switch off while a project is present | warn |
| `index` | age of the cached version index; offline mode on | info only |
| `java-runs` | runs `<active jdk>/bin/java -version` (10 s timeout): catches wrong-architecture or damaged installs | fail |
| `network` | only with `--network`: reaches foojay, services.gradle.org and Maven Central (one small request each, honouring proxy settings) | fail |

`--json`: `{schemaVersion, checks: [{id, level, message, fix}], summary: {ok, warn, fail}}`.
The ids are stable so scripts can filter on them.

Every check is independent and catches its own exceptions, so one broken piece (say an
unreadable `config.toml`) shows as a `fail` line instead of aborting doctor. That matters
because `Context.create` loads the config today, so doctor has to build its context in a way
that survives that.

`doctor` prints fix commands but doesn't run them. A `--fix` can come later, once the fixes
have proved safe.

## Code layout

| Module | New |
|---|---|
| `jkit-core` | `ToolState`, `ToolStates.read` (sources, pin resolution, dangling links); `ShellEnv.fresh(env, home, homes)` for `env`'s PATH and variables |
| `jkit-cli` | `StatusCommand`, `EnvCommand`, `DoctorCommand` with one small class per check behind a `Check` interface (`id()`, `run(ctx) → Result`) |
| `docs/json.md` | the three documents |

## Tests

- `ToolStates`: every source, precedence, a vendor-less pin preferring the default vendor,
  dangling links, outside variables. Uses a fake store in `@TempDir`, as `ProjectFileTest` does.
- `status` and `env`: text and JSON output through `Context.forEnv`, as `JsonOutputTest` does;
  `env` exit 3 with empty stdout when a pin is missing.
- `doctor`: each check against a crafted home (broken link, marker-less folder, outside
  `JAVA_HOME`, a `java` that exits non-zero), plus "a broken config doesn't stop the others".

## Rollout

1. `ToolStates` and `jkit status`: smallest, and the source of truth for the rest.
2. `jkit env`: CI can drop the hook.
3. `jkit doctor`, without `--network`; then `--network`.
4. README ("In the prototype" / "Not yet"), docs/json.md, bash completion.

## Decisions

1. **`env` with a missing pin:** exit 3 with nothing on stdout.
2. **`doctor --network`:** opt-in.
3. **`java-runs`:** on by default.
4. **`doctor --fix`:** not for now.

Until `jkit sync` exists (project files phase 2), status suggests `jkit install …` for a
pin with no matching install, and `jkit use …` for one that isn't applied.

Left out of the first version of doctor: telling which `jkit` the hook calls (it reports
every distinct `jkit` on PATH instead), and the "auto-switch is off" note, which waits for
project files phase 3.
