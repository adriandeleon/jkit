# `--json` output

For editors, IDEs and scripts that drive jkit. With `--json`, a command prints **exactly one JSON
document on stdout**, also when it fails. Progress, download bars and messages go to stderr, so a
program can read stdout whole and show stderr lines as progress.

Commands with `--json`: `list`, `search`, `install`, `upgrade`, `use`, `remove`.
(`reset` and `hook` only make sense in a shell.)

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

Exit codes: `0` success, `1` failure, `2` bad arguments, `3` no installed match (`use`, `remove`).

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

## `jkit install <tool>@<version> [vendor] --json`

```json
{ "schemaVersion": 1, "status": "installed", "verified": true, "installed": { "...": "the install object" } }
```

`status` is `installed` or `already-installed` (then `verified` is null). `verified` is false when the
source publishes no checksum.

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
`verified` and `path`) or `failed` (then `error: {message, hints}` and `to` may be null). Exits `1`
when any line failed.

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

- `kind: link`: a global link; `action` is `set` (`value` is its target) or `remove`.
- `kind: env`: a variable of the calling shell (`--global` changes none); `action` is `set` (with
  `value`) or `unset`. `PATH` is reported per entry, with `action` `add` or `remove`.

## `jkit remove <tool>@<version> [vendor] --json`

```json
{ "schemaVersion": 1, "removed": [ { "...": "the install object, as it was before removal" } ] }
```
