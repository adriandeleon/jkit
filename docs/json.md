# `--json` output

For editors, IDEs and scripts that drive jkit. With `--json`, a command prints **exactly one JSON
document on stdout**, also when it fails. Progress, download bars and messages go to stderr, so a
program can read stdout whole and show stderr lines as progress.

Commands with `--json`: `list`, `status`, `env`, `doctor`, `search`, `install`, `upgrade`, `use`,
`remove`. (`reset` and `hook` only make sense in a shell.) `--help` and `--version` with `--json`
print `{"schemaVersion": 1, "help": "<usage text>"}` (or `"version"`).

## Versioning

Every document starts with `"schemaVersion": 1`. New fields may appear at any time; a change that
removes or renames a field, or changes a field's meaning, bumps `schemaVersion`. Readers should ignore
fields they don't know.

## Errors

Any failure prints this, with the same exit code the process exits with:

```json
{
  "schemaVersion": 1,
  "error": {
    "message": "no installed JDK matches 99 from temurin (default vendor)",
    "hints": ["install it:          jkit install jdk@99"],
    "exitCode": 3
  }
}
```

Exit codes: `0` success, `1` failure, `2` bad arguments (unknown tool, vendor or shell, a version
that isn't one), `3` no installed match (`use`, `remove`, and `env` for a pin with no install).

## The install object

`list`, `install`, `use` and `remove` describe installed builds the same way:

```json
{
  "tool": "jdk",
  "id": "21.0.9+10-temurin",
  "version": "21.0.9+10",
  "major": 21,
  "vendor": "temurin",
  "fx": false,
  "graalvm": false,
  "preRelease": false,
  "home": "/home/me/.jkit/tools/jdk/21.0.9+10-temurin",
  "global": ["jdk"],
  "activeInShell": []
}
```

- `tool` is the store tool: `jdk` (GraalVM builds too, marked `graalvm: true`), `maven` or `gradle`.
- `id` is the install folder's name under `tools/<tool>/`.
- `major` is the feature release: 21 for `21.0.9+10`, 8 for `1.8.0_472`, 3 for Maven `3.9.11`.
- `vendor` is null for Maven and Gradle.
- `home` is the folder to use as `JAVA_HOME`, `MAVEN_HOME` or `GRADLE_HOME`.
- `global` lists the slots (`jdk`, `graalvm`, `maven`, `gradle`) whose global link points here;
  `activeInShell` the slots the calling shell's hook points here.

## `jkit list --json`

Never uses the network.

```json
{
  "schemaVersion": 1,
  "home": "/home/me/.jkit",
  "global": { "jdk": "/home/me/.jkit/tools/jdk/21.0.9+10-temurin", "maven": null, "gradle": null, "graalvm": null },
  "installed": [ { "tool": "jdk", "id": "21.0.9+10-temurin", "...": "the install object" } ]
}
```

`installed` holds JDKs, then Maven, then Gradle, each newest first.

## `jkit status --json`

Never uses the network. Reads the project file found from the working directory (see
[project-files.md](project-files.md)).

```json
{
  "schemaVersion": 1,
  "project": { "dir": "/home/me/src/app", "files": ["/home/me/src/app/jkit.toml"], "warnings": [] },
  "projectError": null,
  "tools": [
    {
      "tool": "jdk",
      "source": "global",
      "home": "/home/me/.jkit/global/jdk",
      "installed": { "tool": "jdk", "id": "17.0.13+11-zulu", "...": "the install object" },
      "version": "17.0.13+11",
      "pin": { "version": "21", "vendor": null, "fx": false },
      "pinInstalled": { "tool": "jdk", "id": "21.0.9+10-temurin", "...": "the install object" },
      "wanted": { "source": "project", "home": "/home/me/.jkit/tools/jdk/21.0.9+10-temurin" },
      "global": { "target": "/home/me/.jkit/tools/jdk/17.0.13+11-zulu", "missing": false },
      "matches": false,
      "hint": "jkit.toml wants jdk 21.0.9+10 temurin; this shell has global: jkit use jdk@21.0.9+10 temurin"
    }
  ]
}
```

- `tools` always has `jdk`, `maven`, `gradle`, `graalvm`, in that order.
- `source` is what this shell has: `shell` (`jkit use`), `project`, `global`, `outside` (a
  `*_HOME` jkit didn't set), `stale` (a jkit install nothing chooses any more) or `none`.
- `wanted.source` is what jkit would pick (shell, then project, then global, else `none`);
  `wanted.home` is null when nothing is picked or a pin has no matching install.
- `home` is the tool's `*_HOME` as set in this environment. `installed` is null when it isn't a
  jkit install; `version` then comes from a JDK's `release` file when it has one.
- `pin` and `pinInstalled` are null without a project pin. `global` is null without a global link.
- `project` is null without a project file. `projectError` is the error message when the
  file can't be read.
- `matches` is true when `source` is what jkit would pick; `hint` says what to do when it isn't.

## `jkit env [--global] [--dir <path>] --json`

Never uses the network. The environment for a new process in `dir`: global links,
overridden by the project's pins (shell choices don't count).

```json
{
  "schemaVersion": 1,
  "dir": "/home/me/src/app",
  "project": { "dir": "/home/me/src/app", "files": ["/home/me/src/app/jkit.toml"], "warnings": [] },
  "variables": {
    "JAVA_HOME": "/home/me/.jkit/tools/jdk/21.0.9+10-temurin",
    "MAVEN_HOME": "/home/me/.jkit/global/maven"
  },
  "pathPrepend": ["/home/me/.jkit/tools/jdk/21.0.9+10-temurin/bin", "/home/me/.jkit/global/maven/bin"],
  "path": "/home/me/.jkit/tools/jdk/21.0.9+10-temurin/bin:/home/me/.jkit/global/maven/bin:/usr/bin",
  "pathSeparator": ":"
}
```

- `variables` holds only the `*_HOME` variables of tools that are chosen; others are left out,
  not unset. `GRAALVM_HOME` follows a GraalVM JDK when no GraalVM is chosen.
- `pathPrepend` is what to put first on PATH; `path` is the whole PATH jkit would set (the
  current PATH with old jkit install folders removed).
- A pin with no matching install is an error document with exit code 3.

## `jkit doctor [--network] --json`

Exit code 1 when any check has level `fail`, 0 otherwise; the document is printed either way.

```json
{
  "schemaVersion": 1,
  "checks": [
    { "id": "home", "level": "ok", "message": "jkit 0.2.1, linux-x64, home ~/.jkit", "fix": null },
    { "id": "links", "level": "fail", "message": "global maven → ~/.jkit/tools/maven/3.9.6, which no longer exists",
      "fix": "jkit use maven@3.9.11 --global, or remove the link: rm ~/.jkit/global/maven" }
  ],
  "summary": { "ok": 9, "info": 2, "warn": 0, "fail": 1 }
}
```

- `level` is `ok`, `info`, `warn` or `fail`; `fix` is null when there is nothing to do.
- A check can report several lines (one per broken link, say). The ids are stable:
  `home`, `hook`, `binary`, `config`, `links`, `outside-vars`, `path-order`, `store`,
  `project`, `index`, `java-runs`, and `network` (only with `--network`).
- Messages and fixes are for people and may change; filter on `id` and `level`.

## `jkit search <tool>[@version] [vendor] --json`

```json
{
  "schemaVersion": 1,
  "tool": "jdk",
  "vendor": "temurin",
  "vendorChosen": false,
  "majors": [ { "major": 25, "lts": true, "maintained": true } ],
  "available": [
    { "tool": "jdk", "version": "25.0.1+8", "major": 25, "vendor": "temurin", "graalvm": false,
      "fx": false, "lts": true, "maintained": true, "preRelease": false, "installed": false }
  ]
}
```

What `available` holds for a JDK (or `graalvm`):

| Arguments | `available` |
| --- | --- |
| `jdk` | the newest build of every vendor and major |
| `jdk@21` | the newest matching build of every vendor |
| `jdk@21 zulu` | every matching build of that vendor |

`vendor` is the default vendor, or the one typed (`vendorChosen: true`). For Maven and Gradle,
`available` holds every matching version (`tool`, `version`, `major`, `preRelease`, `installed`), and
`majors` is empty. Newest first. `--maintained`, `--pre` and `--fx` filter as they do for text output.

## What changed: `changes`

Nothing jkit changes is silent. Every command that changes a file, a folder, a link or a variable
prints a line for each change under its own message, and with `--json` lists them in `changes`
(`install`, `upgrade` per line and for the lookup, `use`, `remove`, `search`):

```json
"changes": [
  { "kind": "folder", "name": "/home/me/.jkit/tools/jdk/21.0.9+10-temurin", "action": "create", "value": null },
  { "kind": "file", "name": "/home/me/.jkit/index/3f9a….cache", "action": "update",
    "value": "https://api.foojay.io/disco/v3.0/packages?…" }
]
```

- `kind: folder`: an install folder, the jkit home on first use, or a leftover of an interrupted
  install; `action` is `create` or `delete`.
- `kind: file`: a cached version list or checksum under `index/`; `action` is `create` or `update`,
  `value` is where it came from.
  Text mode folds these into one `cache` line.
- `kind: link`: a global link; `action` is `set` (`value` is its target) or `remove`.
- `kind: env`: a variable of the calling shell; `action` is `set` (with `value`) or `unset`. `PATH`
  is reported per entry, with `action` `add` or `remove`.

`list`, `status`, `env`, `doctor` and `hook` never change anything.

## `jkit install <tool>@<version> [vendor] --json`

```json
{ "schemaVersion": 1, "status": "installed", "verified": true, "installed": { "...": "the install object" },
  "changes": [ { "kind": "folder", "name": "…/tools/jdk/21.0.9+10-temurin", "action": "create", "value": null } ] }
```

`status` is `installed` or `already-installed` (then `verified` is null). `verified` is false when the
source publishes no checksum, or only an md5 or sha1 one (jkit still checks those, but they don't
count as verified). Downloads and checksums come only over https.

## `jkit upgrade [tool[@major]] [vendor] [--dry-run] --json`

```json
{
  "schemaVersion": 1,
  "dryRun": true,
  "lines": [
    { "tool": "jdk", "major": 21, "vendor": "temurin", "fx": false,
      "from": "21.0.5+11", "to": "21.0.9+10", "status": "available" }
  ]
}
```

One entry per release line. `status`: `up-to-date`, `available` (dry run), `upgraded` (then also
`verified`, `path` and `changes`: the new folder and any global link moved) or `failed` (then `error: {message, hints}` and `to` may be null). Exits `1`
when any line failed. The top-level `changes` lists the cached files written to check the lines.

## `jkit use <tool>@<version> [vendor] [--global] --json`

```json
{ "schemaVersion": 1, "scope": "global", "tool": "jdk", "installed": { "...": "the install object" } }
```

`scope` is `global` or `shell`. Without `--global` the shell hook must be active, as in text mode.

The document also lists what changed outside the store, and notes on what that means (the same
lines text mode prints, also on stderr):

```json
{
  "changes": [
    { "kind": "link", "name": "/home/me/.jkit/global/jdk", "action": "set", "value": "/home/me/.jkit/tools/jdk/25.0.1+8-temurin" }
  ],
  "notes": ["new shells with the jkit hook: JAVA_HOME=~/.jkit/global/jdk, PATH includes ~/.jkit/global/jdk/bin"]
}
```

`--global` changes a link and no variables; without it, only variables of this shell change.

## `jkit remove <tool>@<version> [vendor] --json`

```json
{ "schemaVersion": 1, "removed": [ { "...": "the install object, as it was before removal" } ],
  "changes": [ { "kind": "folder", "name": "…/tools/jdk/17.0.13+11-zulu", "action": "delete", "value": null } ] }
```
