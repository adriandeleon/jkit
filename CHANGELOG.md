# Changelog

All notable changes to jkit are documented in this file. The website's
[What's new](https://jkit-project.dev/whats-new/) pages are generated from it.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`jkit env --install` (Windows) gives every program the global versions.** It sets
  `JAVA_HOME` and the other `*_HOME` variables in your user environment to jkit's global links,
  and puts their `bin` folders first on your user `PATH`, so cmd.exe, IDEs and programs started
  from Explorer follow `jkit use … --global` too, not only a hooked PowerShell. Each change is
  printed, a value it replaces included; the rest of `PATH` stays exactly as it was, `%VAR%`s
  and all. `jkit env --uninstall` removes only what it set. `jkit doctor` says whether it's set
  up, and warns about a `java` in the system `PATH`, which comes first; `jkit use … --global`
  says whether programs outside PowerShell follow it.
- **`jkit prune` keeps builds a running program uses**, such as a JVM, an IDE or a Gradle daemon,
  and says which process; `--json` lists them as `inUse`. A `jkit use` in another shell that runs
  nothing from the build still can't be seen.
- **jkit runs on Alpine.** Each release has static Linux binaries linked against musl,
  `jkit-<version>-linux-x64-musl.tar.gz` and `-linux-aarch64-musl.tar.gz`, which need nothing
  from the system (they also run on glibc distributions older than the 2.34 the other builds
  need). `jkit self-update` on a musl system picks them; before, it refused. They contain musl
  libc and zlib, whose licenses are in `THIRD-PARTY-NOTICES`.
- **The `.deb` and `.rpm` are signed** with the key the Vitrina apt repository signs with
  (`8486A7AF4FC8FEE93D83CDB4EA5FA138C2C09C4A`, at https://vitrina-project.dev/vitrina.asc), so
  apt and dnf users trust one key. After `sudo rpm --import https://vitrina-project.dev/vitrina.asc`,
  `rpm -K` and `dnf` check an `.rpm`; `debsig-verify` checks a `.deb`. Installing without the key
  imported works as before.
- `CONTRIBUTING.md` and issue and pull request templates. The README says why you might pick jkit
  over SDKMAN, mise or asdf (and what it doesn't do), and which hosts jkit contacts, and when: no
  telemetry, no background update checks.

### Changed

- **Temurin and Microsoft JDKs are always checked against the vendor's OpenPGP signature**, the
  `.sig` the vendor publishes next to the download, whether or not foojay lists one. Some current
  Temurin builds (21.0.13+9, 25.0.5+8) installed on foojay's checksum alone. Temurin builds from
  before Adoptium started signing (before 8u342, 11.0.16, 17.0.4, 18.0.2) install on their
  checksum, and jkit says only the checksum was checked.
- **A signed build must be the version asked for.** After a good signature, jkit checks every part
  of a JDK's version, and now Maven's (`lib/maven-core-<version>.jar`) and sbt's too, so a build the
  publisher signed for another version is refused.
- **`jkit install jdk@lts <vendor>`** (and `pin`, and `search` with a vendor) picks that vendor's
  newest LTS release, not the newest LTS of any vendor.
- **`~/.bashrc`, `~/.zshrc`, fish's file, `config.toml` and `jkit.toml` are written atomically**,
  keeping their permissions; a symlinked startup file stays a symlink.
- **`export JKIT_AUTO_SWITCH=0` stops switching on `cd` in a shell that's already open**, and the
  `cd` hook does less work when nothing changes.
- Unpacked JDKs, Maven, Gradle and sbt are no longer group-writable.
- Signature files are downloaded with a 64 KB limit, not up to 2 GB and checked afterwards.
- `jkit self-update` for a `.deb` installed from a file prints a command that downloads into a
  private `mktemp -d` folder instead of a fixed `/tmp` name.
- Building jkit on a JDK older than 25, or a Maven older than the wrapper's, stops at once with a
  clear message.
- **Ctrl-C stops an install or self-update cleanly.** Ctrl-C, SIGTERM or SIGHUP during a download
  deletes the partial file, says so, and then ends jkit by that signal (Windows: exit 130), so a
  shell script running jkit stops too. A SIGINT the shell set to be ignored (a background job)
  stays ignored.
- **Slow downloads start again.** A download that, 30 seconds in, would take more than 10 more
  minutes reconnects once, so a slow CDN edge no longer holds an install for a quarter of an hour.
- **Progress in CI logs.** Without a terminal, download progress is a line at each quarter and at
  least every 30 seconds, so a long download doesn't leave the log silent.
- **Error messages show the home folder as `~`**, like the rest of the output, on every platform.
  Hints keep full paths, since a quoted `~` isn't expanded.
- **`jkit doctor` says where each default vendor comes from**, instead of "from config.toml" for a
  file that doesn't set it.

### Fixed

- **`jkit hook --install` no longer breaks bash or zsh when the startup file has Windows (CRLF)
  line endings**: the block is always LF. `--uninstall` removes only the block and leaves every
  other byte as it was; `jkit pin` and `unpin` keep `jkit.toml`'s line endings too.
- **A `JAVA_HOME` (or other `*_HOME`) you set yourself is no longer lost** when a project or
  `jkit use` overrides it: jkit keeps it in `JKIT_USER_<TOOL>`, says so, and gives it back when
  you leave the project or run `jkit reset`.
- **`jkit exec`: Ctrl-C stops the shell script it runs in.** A command ended by SIGINT, SIGTERM or
  SIGHUP now ends jkit by the same signal, and a Ctrl-C right as the command starts no longer
  leaves it running.
- **zsh: the `cd` hook no longer runs in `$( )` subshells**, so prompt themes don't show jkit
  messages.
- Versions with `-dev`, `-preview`, `-pre` or `-nightly` count as pre-releases, and a build label
  such as `+11-src1` no longer makes a version one.
- An archive entry name the file system can't hold gives the "unsafe to unpack" error instead of
  an unexpected error.
- Proxy passwords in `HTTPS_PROXY` or `JKIT_NETWORK_PROXY` keep a literal `+`.
- **`jkit use`, `reset`, `pin` and `sync` change a Windows PowerShell shell again.** The PowerShell
  hook applied their changes by running a script file, which Windows PowerShell's default
  execution policy blocks, so jkit reported changes the shell never got. It no longer runs a file.
  The README now says how to let Windows PowerShell load the hook from `$PROFILE`.
- **`jkit exec … -- <command>` works in PowerShell with the hook loaded.** PowerShell drops a bare
  `--` from a function's arguments, so jkit saw `jkit exec jdk@21 java -version` and failed with
  `Unknown option: '-version'`. The hook puts the `--` back.
- **jkit no longer offers PowerShell a switch on `cd` it doesn't have.** In PowerShell, `jkit pin`
  and `jkit sync` suggested turning on `shell.auto_switch`, and `jkit config set shell.auto_switch
  true` said new shells would switch on `cd`; PowerShell's hook can't yet, and warned about it in
  every new shell. The suggestion is gone there, and `config set` says it changes nothing in
  PowerShell and that `jkit sync` applies a project's versions.
- **The Windows zip starts without the Visual C++ Redistributable.** It now ships Microsoft's
  `VCRUNTIME140.dll` and `VCRUNTIME140_1.dll` next to `jkit.exe`. `jkit self-update` replaces
  DLLs already there, and never adds them to a folder without them.
- `jkit use jdk@99` and `jkit exec jdk@99` no longer suggest installing a release that doesn't
  exist.
- Every bad `jkit config set` argument exits 2, as an unknown vendor already did.

## [0.4.0] - 2026-11-24

Settings, disk space, self-updates and signature checks.

### Added

- **`jkit config`** lists every setting, its value and where that value comes from (an environment
  variable, `config.toml` or the default). `jkit config set jdk.vendor zulu` and
  `jkit config unset jdk.vendor` change the file and keep its comments.
  [Reference](https://jkit-project.dev/reference/commands/config/)
- **`jkit prune`** frees disk space: older builds in a release line once a newer one is installed
  (never one that is global, used in this shell or pinned here), folders left by interrupted
  installs, and cached version lists older than 30 days. `--dry-run` shows what would go and how
  much space it frees. [Reference](https://jkit-project.dev/reference/commands/prune/)
- **`jkit self-update`** updates jkit itself. A jkit from a release archive is replaced in place
  after checking the download against the release's `SHA256SUMS`, which must be signed by jkit's
  release key (`SHA256SUMS.asc`, built into jkit); for Homebrew, apt, dnf and winget it prints
  that tool's upgrade command. `--check` only says whether a newer jkit is out.
  [Reference](https://jkit-project.dev/reference/commands/self-update/)
- **`jkit search` marks what `jkit install` would refuse.** `○` marks a build with no published
  checksum and no signature jkit checks (it needs `--allow-unverified`), and a blank cell one
  with no direct download, such as Oracle 11. `--json` adds `downloadable` and `verifiable` to
  each build. [Reference](https://jkit-project.dev/reference/commands/search/)

### Changed

- **`eval "$(jkit env)"` fails when `jkit env` does.** On failure it prints `false` on stdout
  (`throw '…'` for PowerShell), so a `set -e` script or CI step stops there.
  [Reference](https://jkit-project.dev/reference/commands/env/)

### Fixed

- Corretto 8 installs again: jkit reads its `version.txt`, as it has no `release` file.
- `jkit self-update --check` works while the newest release has no signature.
- `jkit upgrade jdk zuluu` says `unknown JDK vendor` with a suggestion (exit 2) instead of finding
  nothing to upgrade.
- `jkit config set jdk.vendor "SAP Machine"` accepts foojay's other names for a vendor.
- `jkit config set` and `jkit config unset` can repair a `config.toml` with a wrong value.
- `jkit prune` carries on past a build it can't remove.

### Security

- **OpenPGP signature checks.** Temurin, Microsoft and Corretto JDKs, Maven 3.9.0 and later, and
  sbt are now checked against their publisher's signature, with keys built into jkit, on top of
  the checksum. A signature that doesn't match stops the install. So does one jkit can't fetch,
  read, or tie to the publisher's keys, unless you pass `--allow-unverified`, which installs on
  the checksum alone, marked unverified. Expect that with a Maven release by a new Apache release
  manager, until a jkit release adds their key. Corretto publishes no checksum, so its signature
  is the check, and Corretto installs no longer need `--allow-unverified`.
  [Security](https://jkit-project.dev/reference/security/)

## [0.3.0] - 2026-11-24

The first public release.

### Added

- **Project files and switching on `cd`.** `jkit pin`, `jkit unpin` and `jkit sync` write and
  apply a `jkit.toml`. With auto-switch on, bash, zsh and fish switch to a project's versions when
  you `cd` into it, and back when you leave. [Projects](https://jkit-project.dev/guides/projects/)
- **`jkit hook --install`** adds the hook to `~/.bashrc`, `~/.zshrc` or fish's `conf.d` for you,
  and `--uninstall` takes it out.
- **sbt**, next to the JDKs, GraalVM, Maven and Gradle.
- **`jkit exec`** runs one command with the right versions and no shell change, for scripts, CI
  and AI agents. [AI agents](https://jkit-project.dev/guides/ai-agents/)
- **`jkit status`, `jkit doctor` and `jkit env`** say which version is active here and why, check
  the setup and say how to fix each problem, and print the environment for CI and IDEs.
  [Troubleshooting](https://jkit-project.dev/help/troubleshooting/)
- **Packages**: Homebrew, apt (through the Vitrina repository), winget, `.deb`, `.rpm`, and zips
  for every platform. macOS binaries are signed and notarized.

### Changed

- **Every change is printed**: each file, folder, link and variable a command touches.

### Fixed

- Many fixes from security and correctness reviews: archive extraction, installs, network
  handling, shells, and the release pipeline.

## [0.2.2] - 2026-11-22

### Added

- Reads project files: `jkit.toml`, `.sdkmanrc`, `.tool-versions` and `.java-version`.
- `--json` output for editors and scripts. [JSON output](https://jkit-project.dev/reference/json/)
- Reports environment and link changes after each command.
- Signed and notarized macOS binaries for arm64 and x64 (macOS 15 or newer).

## [0.2.0] - 2026-11-20

### Added

- `jkit upgrade`: the newest build of each installed major version.
- `jkit search jdk` shows every vendor against every major version as a grid.
- Bash tab completion.

### Changed

- JavaFX builds and plain builds are kept apart.

## [0.1.1] - 2026-11-20

### Fixed

- Clear errors for unknown vendors and network failures.

## [0.1.0] - 2026-11-20

The first build: install and switch JDKs, Maven and Gradle.

[Unreleased]: https://github.com/adriandeleon/jkit/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/adriandeleon/jkit/releases/tag/v0.4.0
[0.3.0]: https://github.com/adriandeleon/jkit/releases/tag/v0.3.0
[0.2.2]: https://github.com/adriandeleon/jkit/releases/tag/v0.2.2
[0.2.0]: https://github.com/adriandeleon/jkit/releases/tag/v0.2.0
[0.1.1]: https://github.com/adriandeleon/jkit/releases/tag/v0.1.1
[0.1.0]: https://github.com/adriandeleon/jkit/releases/tag/v0.1.0
