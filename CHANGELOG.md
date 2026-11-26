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
- **jkit runs on Alpine (x64).** Each release has a static Linux x64 binary linked against musl,
  `jkit-<version>-linux-x64-musl.tar.gz`, which needs nothing from the system (it also runs on
  glibc distributions older than the 2.34 the other builds need). `jkit self-update` on a musl
  system picks it; before, it refused. There's no arm64 musl build: GraalVM's native-image can't
  make one yet. It contains musl libc and zlib, whose licenses are in `THIRD-PARTY-NOTICES`.
- **`jkit doctor` reports leftover `*.jkit-tmp` files** from an interrupted write of a startup
  file, `config.toml` or `jkit.toml` (check `temp-files`), with the command that removes them.
- **`jkit reset` takes `--json`**, with or without `--global`.

### Changed

- **macOS builds need macOS 14 (Sonoma) or newer**, down from 15; the Homebrew formula says so too.
- **Installs don't wait on each other's downloads.** The install lock is held only to look at and
  change `tools/`, so parallel installs (two terminals, CI jobs sharing a home) download side by
  side, and `remove`, `prune` and `self-update` don't wait for a download. `prune` and `doctor`
  leave alone the folder a running install downloads into. On file systems without locks (some
  NFS), they name the folders they can't tell about, with their age, and delete none of them.
- **A first install in a shell with the hook sets `JAVA_HOME` right away** (or the tool's
  `*_HOME`), and says so; `jkit use … --global` does the same when it was unset. Before, it
  suggested `jkit reset jdk`, which did nothing. Right after `jkit hook --install`, jkit says to open
  a new shell instead of suggesting `jkit hook --install` again.
- **Shorter install output.** No folder lines that the "installed → folder" line already names,
  and no cache line (`--json` keeps both); an install refused for its signature is refused before
  the download, and "downloading …" is printed only once it starts.
- **Every Mandrel build is listed and installable**, as `<java>+<mandrel>` (`graalvm@21 mandrel`
  → `21+23.1.13`); before, foojay's builds of one Java release showed as one. Existing Mandrel
  installs keep working, and `jkit upgrade` offers the newest. Builds a vendor re-released under
  the same version (Corretto 11.0.20.9.1 after 11.0.20.8.1, Zulu, Liberica and others) resolve to
  the newest instead of whichever foojay listed first.
- After `pin` and `sync`, the hint for switching on `cd` is `jkit config set shell.auto_switch true`.
- `jkit doctor` suggests `jkit prune` for leftovers in `tmp/` instead of `rm -rf`.
- **`jkit install jdk@lts <vendor>`** (and `pin`, and `search` with a vendor) picks that vendor's
  newest LTS release, not the newest LTS of any vendor.
- **`~/.bashrc`, `~/.zshrc`, fish's file, `config.toml` and `jkit.toml` are written atomically**
  and synced to disk, keeping their permissions, owner and encoding (UTF-8 with or without a BOM,
  UTF-16, Latin-1); a symlinked startup file stays a symlink, and a read-only file is refused. On
  Windows, a file another program has open is retried, then written in place.
- **PATH follows a `*_HOME` you set yourself.** When the hook loads, and when you leave a project
  or run `jkit reset`, that home's `bin` comes first instead of jkit's global one, so `java` is
  `$JAVA_HOME/bin/java`, also for a home inside `~/.jkit/tools`. A `*_HOME` you change by hand
  while a project or `jkit use` applies is left as you set it when jkit's choice goes, and jkit
  says so. A trailing slash on it no longer puts its `bin` on PATH twice.
- **`export JKIT_AUTO_SWITCH=0` stops switching on `cd` in a shell that's already open**, and the
  `cd` hook does less work when nothing changes.
- **Ctrl-C stops an install or self-update cleanly.** Ctrl-C, SIGTERM or SIGHUP during a download
  deletes the partial file and says so. Ctrl-C then ends jkit by SIGINT, so a shell script running
  jkit stops too (exit 130 on Windows, or as a container's PID 1); SIGTERM and SIGHUP exit 143 and
  129. A SIGINT the shell set to be ignored (a background job) stays ignored. A Ctrl-C while the
  JDK unpacks no longer races jkit's cleanup: no "file not found" error, nothing left in `tmp/`, no
  partial install in `tools/`.
- **Slow downloads start again.** A download that, 30 seconds in, would take more than 10 more
  minutes reconnects once, so a slow CDN edge no longer holds an install for a quarter of an hour.
- **Progress in CI logs.** Without a terminal, download progress is a line at each quarter and at
  least every 30 seconds, so a long download doesn't leave the log silent.
- **Error messages show the home folder as `~`**, like the rest of the output, on every platform.
  Hints keep full paths, since a quoted `~` isn't expanded.
- **`jkit doctor` says where each default vendor comes from**, instead of "from config.toml" for a
  file that doesn't set it.
- Project: GitHub releases list the version's changelog entry and how to install it, instead of
  a list of pull requests; building jkit on a JDK older than 25, or a Maven older than the
  wrapper's, stops at once with a clear message.
- **PATH entries that move are reported** (`PATH  moved … ahead of …`, action `move` in
  `--json`), since a move changes which program runs; before, only added and removed entries were.
  Empty PATH entries (the current folder) are kept instead of dropped without a word.
- **`.tool-versions` reads more asdf and mise Java ids**: Corretto's `21.0.5.11.1` numbering,
  Semeru's `_openj9-…` suffix, Mandrel's `mandrel-<release>-java<N>`, `-javafx`, and `-jre`,
  `-lite` and `-musl` builds (pinned as the vendor's full JDK, with a warning). A Zulu release
  number such as `zulu-21.38.21` pins the major, with a warning, and an unknown vendor is warned
  about when the file is read instead of failing at `sync`.
- **`jkit env --install` (Windows) keeps a `JAVA_HOME` or other `*_HOME` of your own** that it
  replaces, in `JKIT_SAVED_<TOOL>`, and `jkit env --uninstall` puts it back; each change is printed.
- `jkit search` says when nothing matches (`no jdk build matches jdk@21.0.99`) and what to search
  for instead, rather than printing an empty table or only the legend.
- When `jkit install` finds no matching build, "available from:" lists only vendors that have that
  version; when none has it, the hint says so and names the newest build of that release.
- `jkit self-update` no longer holds the install lock while it downloads and tests the new binary,
  so installs, `remove` and `prune` elsewhere don't wait for it.
- With several project files, hints and warnings name the file that pins each tool, and the `cd`
  line names the files properly (`~/p (.sdkmanrc, .tool-versions)`).
- `jkit remove` says what uses a build ("the global maven", "this shell's jdk (chosen with jkit
  use)"), and mentions the link `--force` removes only when there is one.
- Leaving a project says `maven unset (no global maven)` for a tool with no global version, instead
  of "back to the global versions".
- The `.deb` and `.rpm` descriptions say to set up the shell with `jkit hook --install`.

### Fixed

- **`jdk = 21` in `jkit.toml` (a TOML number, not a string) pins JDK 21.** Before, it was skipped
  with a warning and `jkit sync` passed, saying the project pins no tools, so CI went on with the
  wrong JDK. A decimal such as `maven = 3.10`, which TOML reads as 3.1, is refused with a warning to
  quote it, and `jkit sync` now fails, installing nothing, when a file pins a tool it can't read.
- A lookup no longer fails when its answer can't be saved to the cache (a full disk, a read-only
  folder); jkit warns once and carries on.
- An archive with colliding entries (a file and a folder of the same name) is refused as a broken
  archive, instead of an error about something in the way in `~/.jkit/tmp`.
- On Windows, `jkit self-update` no longer fails when an older jkit that is still running holds
  `jkit.exe.old`: the old binary is set aside as `jkit.exe.1.old` (and so on), and the next
  self-update deletes them all.
- `jkit env --install --json` and `--uninstall --json` include `schemaVersion`, like every other
  document.
- A build from main says 0.5.0-SNAPSHOT, not 0.4.0-SNAPSHOT, so `jkit self-update` doesn't take it
  for an older version than 0.4.0.
- **`jkit hook --install` no longer breaks bash or zsh when the startup file has Windows (CRLF)
  line endings**: the block is always LF. `--uninstall` removes only the block and leaves every
  other byte as it was; `jkit pin` and `unpin` keep `jkit.toml`'s line endings too.
- **A `JAVA_HOME` (or other `*_HOME`) you set yourself is no longer lost** when a project or
  `jkit use` overrides it: jkit keeps it in `JKIT_USER_<TOOL>`, says so, and gives it back when
  you leave the project or run `jkit reset`.
- **`jkit exec`: Ctrl-C stops the shell script it runs in.** After a real Ctrl-C, jkit ends by
  SIGINT like its command; a command that only exits 129, 130 or 143 passes that code on, and
  SIGTERM or SIGHUP give 143 or 129, as documented. A Ctrl-C right as the command starts no longer
  leaves it running.
- **`jkit reset <tool>` leaves a `JAVA_HOME` you set yourself alone** when jkit hadn't chosen that
  tool, and says there was nothing to reset.
- **`jkit hook --install` and `--uninstall` find jkit's block when it's indented** or has trailing
  spaces; with its end marker missing, both say so and change nothing.
- **PowerShell: `use`, `reset`, `pin`, `sync` and `exec` work with the hook loaded.**
  - The hook applied changes by running a script file, which Windows PowerShell's default
    execution policy blocks, so jkit reported changes the shell never got; it no longer runs a
    file. [docs/windows.md](docs/windows.md) says how to let Windows PowerShell load the hook from
    `$PROFILE`.
  - PowerShell drops a bare `--` from a function's arguments, so `jkit exec jdk@21 -- java
    -version` failed with `Unknown option: '-version'`; the hook puts the `--` back.
  - `jkit pin` and `jkit sync` no longer suggest `shell.auto_switch`, which PowerShell's hook
    can't do yet (and warned about in every new shell); `jkit config set shell.auto_switch true`
    says it changes nothing in PowerShell and that `jkit sync` applies a project's versions.
- **The Windows zip starts without the Visual C++ Redistributable.** It now ships Microsoft's
  `VCRUNTIME140.dll` and `VCRUNTIME140_1.dll` next to `jkit.exe`. `jkit self-update` replaces
  DLLs already there, and never adds them to a folder without them.
- Corretto's catch-all "latest" entries no longer show up in `search` or get picked by `install`.
- Ctrl-C while copying an install to another disk no longer leaves `tools/.<name>.partial` behind.
- **zsh: the `cd` hook no longer runs in `$( )` subshells**, so prompt themes don't show jkit
  messages.
- Versions with `-dev`, `-preview`, `-pre` or `-nightly` count as pre-releases, and a build label
  such as `+11-src1` no longer makes a version one.
- An archive entry name the file system can't hold gives the "unsafe to unpack" error instead of
  an unexpected error.
- Proxy passwords in `HTTPS_PROXY` or `JKIT_NETWORK_PROXY` keep a literal `+`.
- `jkit use jdk@99` and `jkit exec jdk@99` no longer suggest installing a release that doesn't
  exist.
- Every bad `jkit config set` argument exits 2, as an unknown vendor already did.
- `jkit hook --uninstall` refuses, instead of deleting your lines, when a stray `# >>> jkit >>>`
  marker sits before jkit's block; `jkit doctor` finds the temporary files of an interrupted write
  next to a startup file that is a symlink.
- An install that lost a race to another jkit no longer suggests making it the default when it
  already is.
- `jkit hook --help` examples line up, and `jkit exec --help` mentions SIGHUP.

### Security

- **A signature counts only when its key may sign and is strong enough**: marked for signing
  (by its self-signature, or for a subkey its binding signature), RSA of 2048 bits or more, EdDSA
  or ECDSA, and not expired when it signed. Before, an encryption subkey, an expired subkey, or
  Guillaume Nodet's old DSA-1024 key in jkit's Maven keys would have passed.
- **Temurin and Microsoft JDKs are always checked against the vendor's OpenPGP signature**, the
  `.sig` the vendor publishes next to the download, whether or not foojay lists one. Some current
  Temurin builds (21.0.13+9, 25.0.5+8) installed on foojay's checksum alone. Temurin builds from
  before Adoptium started signing (before 8u342, 11.0.16, 17.0.4, 18.0.2) install on their
  checksum, and jkit says only the checksum was checked. A signing vendor's download link with no
  file name, or one in its query string, is refused rather than installed without a signature.
- **foojay can't steer a JDK around its signature check.** jkit takes only builds of the vendor and
  major release you asked for, and takes Temurin, Microsoft and Corretto downloads only from the
  vendor's own release links, in their exact shape and with the listed file name (Adoptium's
  GitHub releases, aka.ms/download-jdk, corretto.aws); a build listed anywhere else is refused.
  GitHub serves a fork's commits under `github.com/adoptium/…/archive/`, so a host check alone
  wasn't enough. A foojay mirror of your own (`JKIT_SOURCES_FOOJAY_URL`, https) may serve the
  builds these vendors signed, whose signature and version are still checked.
  [SECURITY.md](SECURITY.md) says what that does and doesn't guarantee.
- **A signed build must be the version asked for.** After a good signature, jkit checks every part
  of a JDK's version, and now Maven's (`lib/maven-core-<version>.jar`) and sbt's too, so a build the
  publisher signed for another version is refused.
- **Corretto builds from 2019** (8u222, 8u232, 11.0.4, 11.0.5), signed with Amazon's since-retired
  keys, are refused with a message that says so and suggests a newer build or
  `--allow-unverified`, instead of a self-update; `jkit search` marks them ○.
- **The `.deb` and `.rpm` are signed** with the key the Vitrina apt repository signs with
  (`8486A7AF4FC8FEE93D83CDB4EA5FA138C2C09C4A`, at https://vitrina-project.dev/vitrina.asc), so
  apt and dnf users trust one key. After `sudo rpm --import https://vitrina-project.dev/vitrina.asc`,
  `rpm -K` and `dnf` check an `.rpm`; `debsig-verify` checks a `.deb`. Installing without the key
  imported works as before.
- Signature files are downloaded with a 64 KB limit, not up to 2 GB and checked afterwards.
- Unpacked JDKs, Maven, Gradle and sbt are no longer group-writable.
- `jkit self-update` for a `.deb` installed from a file prints a command that downloads into a
  private `mktemp -d` folder instead of a fixed `/tmp` name.

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
