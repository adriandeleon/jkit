<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/jkit-wordmark-dark.svg">
    <img src="branding/jkit-wordmark.svg" alt="jkit" height="72">
  </picture>
</h1>

[![CI](https://github.com/adriandeleon/jkit/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/adriandeleon/jkit/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/adriandeleon/jkit?sort=semver)](https://github.com/adriandeleon/jkit/releases/latest)
[![Platforms](https://img.shields.io/badge/platforms-Linux%20%7C%20macOS%20%7C%20Windows-informational)](#install)
[![License: MIT](https://img.shields.io/github/license/adriandeleon/jkit)](LICENSE)

Installs and switches JDKs from 20+ vendors, Maven, Gradle and sbt: per shell, per project or
globally. One native binary, no JDK needed to run it.

Website: <https://jkit-project.dev>

## Quickstart

```bash
brew install adriandeleon/tap/jkit     # or see Install below
jkit hook --install                    # adds a short marked block to ~/.bashrc, ~/.zshrc or fish's config
# open a new shell, then:
jkit install jdk@21                    # the first JDK you install becomes the default
java -version
```

Then, as needed:

```bash
jkit search jdk                        # vendors and releases (jkit search jdk@21 zulu: every build)
jkit install jdk@lts zulu              # newest long-term-support release from a vendor
jkit use jdk@21 --global               # the default for new shells
jkit use jdk@17 zulu                   # only this shell
jkit install maven                     # also gradle@8.14, sbt
jkit pin jdk@21                        # record it in this project's jkit.toml
jkit exec jdk@17 -- mvn test           # one command, no shell change (scripts, CI, agents)
jkit status                            # what's active here, and why
jkit doctor                            # what's wrong with the setup, and how to fix it
jkit upgrade                           # newest build within each installed release (21.0.5 → 21.0.9)
jkit prune --dry-run                   # what old builds, leftovers and old cache prune would remove
jkit self-update                       # jkit itself (or how to, for brew, apt, dnf and winget)
```

## Why jkit

Next to SDKMAN, mise and asdf, jkit is narrower and stricter:

- One native binary. Running it needs no JDK, bash or plugins.
- Every download is checked against its published checksum; Temurin, Microsoft and Corretto JDKs,
  Maven 3.9.0 and later, and sbt also against their publisher's OpenPGP signature, with the keys
  built into jkit.
- JDKs from 20+ vendors through foojay. Projects keep their `.sdkmanrc`, `.tool-versions` or
  `.java-version`: jkit reads them.
- `jkit exec` and `--json` for scripts, CI, editors and agents.
- Every change it makes is printed: folders, links, variables.

What it doesn't do: it manages JDKs, GraalVM, Maven, Gradle and sbt, nothing else (no Kotlin,
JBang, Scala, Node, …); SDKMAN, mise and asdf cover far more tools. Windows support is a preview.

## Install

| Platform | |
| --- | --- |
| macOS (Apple Silicon, Intel), Linux | `brew install adriandeleon/tap/jkit` |
| Debian, Ubuntu (x64, arm64) | `sudo apt install jkit` from the [Vitrina](https://vitrina-project.dev) apt repository, so `apt upgrade` keeps it current ([setup](https://jkit-project.dev/install/#apt)); or the `.deb` from the [releases](https://github.com/adriandeleon/jkit/releases): `sudo apt install ./jkit_*_amd64.deb` (`_arm64.deb` on ARM) |
| Fedora, RHEL, Rocky, Alma 9+ | the `.rpm`: `sudo dnf install ./jkit-*.x86_64.rpm` (`.aarch64.rpm` on ARM) |
| Windows x64 (preview) | `winget install AdrianDeLeon.jkit` (once winget-pkgs has merged the release; until then, the `.zip`) |
| Anything else | a `.tar.gz` or `.zip` from the releases |

Each archive holds `jkit` (or `jkit.exe`), `LICENSE` and `THIRD-PARTY-NOTICES`; put `jkit`
anywhere on your `PATH`. From the release after 0.4.0, the Windows `.zip` also holds
`vcruntime140.dll` and `vcruntime140_1.dll`, the Visual C++ runtime `jkit.exe` needs: keep them
next to it, in a folder of its own. Linux builds, Homebrew's included, need glibc 2.34 or newer and zlib
(Debian 12+, Ubuntu 22.04+, RHEL 9+; not Ubuntu 20.04, RHEL 8 or Alpine);
macOS builds need macOS 15 or newer and are signed and notarized. Pre-releases (versions with a
hyphen) are on the releases page only.

`jkit self-update` updates a jkit from a `.tar.gz` or `.zip` in place, after checking it against
the release's `SHA256SUMS` and that file's signature by jkit's release key; for Homebrew, apt, dnf
and winget it prints that tool's upgrade command (for a `.deb` installed from a file, the download
that replaces it).

To check a download: `sha256sum -c SHA256SUMS --ignore-missing` (from 0.4.0, `gpg --verify
SHA256SUMS.asc SHA256SUMS` checks that file against jkit's release key, in
[SECURITY.md](SECURITY.md)), and for where it was built,
`gh attestation verify <file> --repo adriandeleon/jkit`. Each release also has a CycloneDX SBOM,
made from the Linux x64 build: the other builds compile in the same libraries, and the macOS x64
binary is built with Liberica NIK instead of GraalVM CE.

### Windows (preview)

jkit runs on Windows, but this part is less tested than macOS and Linux:

- Add the hook to your PowerShell profile by hand (`jkit hook --install` doesn't do PowerShell
  yet): `notepad $PROFILE`, then add `jkit hook pwsh | Out-String | Invoke-Expression`.
  Windows PowerShell (5.1, the one Windows comes with) runs no profile until scripts are allowed:
  `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`. PowerShell 7 allows them already.
- `jkit use … --global` points `%USERPROFILE%\.jkit\global\jdk` at the install. For programs
  started outside a hooked PowerShell (cmd.exe, IDEs, Explorer) to follow it too, run
  `jkit env --install` once: it sets `JAVA_HOME` and the other `*_HOME` variables in your user
  environment to the global links and puts their `bin` folders first on your user `PATH`, so a
  later `jkit use … --global` reaches new programs with no further change. Each change is printed;
  `jkit env --uninstall` takes out only what it set. A `java` on the system `PATH` still comes
  first; `jkit doctor` says if there is one.
- Switching on `cd` isn't available in PowerShell yet.
- Windows on ARM runs the x64 build under emulation.
- `jkit.exe` needs the Microsoft Visual C++ runtime. winget installs the Visual C++
  Redistributable (2015 or newer, x64) with jkit. From the release after 0.4.0 the `.zip` carries
  the runtime's two DLLs next to `jkit.exe`, and `jkit self-update` keeps them up to date; with
  0.4.0 and earlier, install the Redistributable first. Once the Redistributable is installed you
  can delete the two DLLs, and Windows Update keeps the runtime patched.

## How it works

- Installs go to `~/.jkit/tools/<tool>/<version>`; nothing outside `~/.jkit` is touched except the
  short marked block `jkit hook --install` adds to your shell's startup file (`--uninstall` removes it).
- The global default is a link, `~/.jkit/global/<tool>`, which the hook puts on `PATH` and uses for
  `JAVA_HOME`, `MAVEN_HOME`, `GRADLE_HOME`, `SBT_HOME` and `GRAALVM_HOME`. Point an IDE at
  `~/.jkit/global/jdk` and it follows `jkit use … --global`.
- `jkit use` without `--global` changes only the current shell, through the hook.
- Every change jkit makes is printed: folders, links, cache files, and each variable it sets.
- Downloads come over https only and are checked against the checksum their source publishes:
  SHA-256 or stronger counts as verified; old Maven releases that only have SHA-1 install with a
  warning, marked unverified; a download with no checksum is refused unless you pass
  `--allow-unverified`. Temurin, Microsoft and Corretto builds, Maven 3.9.0 and later, and sbt
  are also checked against their publisher's OpenPGP signature, with keys built into jkit; a
  signature that is missing, doesn't match, or isn't by the publisher's key stops the install
  (`--allow-unverified` installs it anyway, marked unverified). Corretto publishes no checksum,
  so its signature is the check. `jkit search` marks builds with neither as ○. See [SECURITY.md](SECURITY.md).
- No telemetry and no background update checks. Only commands that need version lists or
  downloads go online (`install`, `search`, `sync`, `upgrade`, `pin`, `config set`, `self-update`,
  `doctor --network`), and only to the sources below: foojay and the vendor download it lists,
  services.gradle.org, Maven Central, and GitHub (sbt's and jkit's own releases). `use`, `status`,
  `list`, `env`, `exec`, `doctor` and the shell hook never do. With `--offline` or
  `JKIT_NETWORK_OFFLINE=1`, version lists come only from the cache.

| Path | What |
| --- | --- |
| `~/.jkit/tools/` | installed JDKs, Maven, Gradle and sbt |
| `~/.jkit/global/` | links to the global defaults |
| `~/.jkit/index/` | version lists, cached 24 h (`--refresh` refetches, `--offline` uses only the cache) |
| `~/.jkit/config.toml` | settings (below) |

`JKIT_HOME` moves all of it. It must be an absolute path to a folder you (or root) own that other users can't write to.

### Settings

`~/.jkit/config.toml`:

```toml
[jdk]
vendor = "temurin"        # default JDK vendor
[graalvm]
vendor = "graalvm-ce"     # default GraalVM vendor
[shell]
auto_switch = true        # switch to a project's versions on cd (default false; jkit hook --install --auto-switch)
```

`jkit config` lists each setting, its value and where it comes from; `jkit config set jdk.vendor zulu`
and `jkit config unset jdk.vendor` change the file, keeping its comments.

Environment variables: `JKIT_HOME`, `JKIT_JDK_VENDOR`, `JKIT_GRAALVM_VENDOR`, `JKIT_AUTO_SWITCH`
(`1`/`0`), `JKIT_PROJECT_FILE` (use this project file, or none when empty), `JKIT_NETWORK_OFFLINE` (`1`/`true`: like `--offline`),
`HTTPS_PROXY`/`NO_PROXY` (or `JKIT_NETWORK_PROXY`), and `JKIT_SOURCES_{FOOJAY,GRADLE,MAVEN,SBT,RELEASES}_URL`
for https mirrors.

## Projects

A `jkit.toml` (or an existing `.sdkmanrc`, `.tool-versions` or `.java-version`) records a project's
versions. `jkit pin jdk@21` writes it, `jkit sync` installs what it asks for and switches this shell,
and with auto-switch on, bash, zsh and fish switch on `cd`. See
[docs/project-files.md](docs/project-files.md).

## More

- [docs/troubleshooting.md](docs/troubleshooting.md): `status`, `doctor` and `env`.
- [docs/agents.md](docs/agents.md): using jkit from scripts, CI and AI agents (`jkit exec`).
- [docs/json.md](docs/json.md): `--json` output for editors and tools.
- Editors: [Editora](https://editora-project.dev/) 0.29.0 and later drive jkit for every command:
  project pins, installs, `sync`, `status`, `doctor`, `prune`, settings and the shell hook. See
  [Editora's jkit docs](https://editora-project.dev/docs/build-tools/#jkit).
- `jkit <command> --help` for every option. Tab completion comes with the hook (bash, and zsh
  through bashcompinit).

Sources: [foojay](https://foojay.io) for JDKs, services.gradle.org, Maven Central, and sbt's GitHub
releases.

## Uninstall

```bash
jkit hook --uninstall          # takes the line out of your shell's startup file
rm -rf ~/.jkit                 # every install, link and setting
brew uninstall jkit            # or: sudo apt remove jkit, sudo dnf remove jkit, winget uninstall AdrianDeLeon.jkit
```

## Not yet

Switching on `cd` in PowerShell, `jkit hook --install` for PowerShell, native fish and zsh
completion, signature checks for other JDK vendors (Gradle publishes no signatures), and
Windows on ARM natively.

## Build

Needs JDK 25; Maven comes through the wrapper (its checksum is pinned in
`.mvn/wrapper/maven-wrapper.properties`).

```bash
./mvnw verify                                          # compile + unit tests
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative package -DskipTests
# binary: jkit-cli/target/jkit
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative,linux-packages package -DskipTests
# jkit-cli/target/jkit_<version>_amd64.deb and jkit-<version>-<release>.x86_64.rpm
```

The `.deb` and `.rpm` are built in pure Java (jdeb, rpm-builder), for the build machine's
architecture. To try a build without touching your own setup, give it a home of its own:
`export JKIT_HOME="$(mktemp -d)"`.

When dependencies change, update `packaging/third-party/components.tsv` and regenerate the notices
with `packaging/third-party-notices.sh` (CI runs it with `--check`).

| Module | Contents |
| --- | --- |
| `jkit-model` | ToolSpec, Version, Vendor, Platform; no I/O |
| `jkit-sources` | Http (+ cache), foojay, Gradle, Maven Central and sbt clients |
| `jkit-core` | Store, Installer, Archives, Config, ShellEnv, project files |
| `jkit-platform` | OsServices: links, shell hooks (Unix, Windows) |
| `jkit-cli` | picocli commands and the native binary |

To contribute, see [CONTRIBUTING.md](CONTRIBUTING.md); design notes are in [docs/design/](docs/design/).

## License

MIT, see [LICENSE](LICENSE). Bundled components are listed in
[THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES).
