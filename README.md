# jkit (prototype)

Installs and switches JDKs from several vendors, Maven, Gradle and sbt. Single native binary,
built with Java 25, picocli and GraalVM native-image. See the design spec for the full plan.

Website: <https://jkit-project.dev>

## Build

Maven, through the Maven Wrapper (Maven 3.9.16, checksum pinned in `.mvn/wrapper/maven-wrapper.properties`).

```bash
./mvnw verify                                          # compile + unit tests on JDK 25
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative package -DskipTests
# binary: jkit-cli/target/jkit
```

Building needs JDK 25; the native binary itself needs no JDK.

### Linux packages (.deb and .rpm)

```bash
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative,linux-packages package -DskipTests
# jkit-cli/target/jkit_<version>_amd64.deb
# jkit-cli/target/jkit-<version>-<release>.x86_64.rpm
```

Both are built in pure Java (jdeb, rpm-builder), so no `dpkg-deb` or `rpmbuild` is needed.
They install `/usr/bin/jkit`, its bash completion and docs, and need glibc 2.34 or newer and zlib
(Debian 12+, Ubuntu 22.04+, Fedora, RHEL/Rocky/Alma 9+). The architecture comes from the build
machine: build on arm64 for arm64 packages.

```bash
sudo apt install ./jkit_*_amd64.deb      # Debian, Ubuntu
sudo dnf install ./jkit-*.x86_64.rpm     # Fedora, RHEL, Rocky, Alma
```

### macOS

Releases include `jkit-<version>-macos-aarch64.tar.gz` (Apple Silicon) and
`jkit-<version>-macos-x64.tar.gz` (Intel): a single binary. They need macOS 15 (Sequoia) or
newer.

```bash
tar -xzf jkit-*-macos-*.tar.gz
sudo mv jkit /usr/local/bin/
```

The binaries are not signed or notarized yet, so Gatekeeper blocks a copy downloaded with a
browser; run `xattr -d com.apple.quarantine jkit` on it first.

## Try it

```bash
export JKIT_HOME=/tmp/jkit-home          # keep tests away from ~/.jkit
eval "$(jkit hook bash)"                 # shell wrapper for `jkit use`, plus tab completion
jkit search jdk
jkit install jdk@21                      # default vendor (temurin)
jkit install jdk@17 zulu
jkit use jdk@21 --global
jkit use jdk@17 zulu                     # this shell only
jkit install graalvm@25 && jkit use graalvm@25   # sets GRAALVM_HOME only
jkit list
jkit upgrade --dry-run                   # newest build within each installed major (21.0.5 → 21.0.9)
jkit upgrade                             # installs them and moves --global along
jkit reset jdk
jkit remove jdk@17 zulu
```

## In the prototype

search, install, upgrade, use (shell and `--global`), reset, list, status, env, doctor, remove, copyright, hook (bash/zsh/fish; pwsh untested),
and bash tab completion (`jkit completion bash`) for commands, options, tools and vendors.
`status` shows which version of each tool is active here and where it comes from (this shell, a
project file, the global default, or something set outside jkit), with a hint when that isn't what
jkit would pick. `env` prints the environment for a new process here, for CI and IDEs
without the hook (`eval "$(jkit env)"`). `doctor` checks the setup (shell hook, PATH order, `JAVA_HOME`
set outside jkit, broken links, leftovers, whether the active JDK runs; `--network` for the
sources) and prints a fix for each problem. Project files (`jkit.toml`, `.sdkmanrc`, `.tool-versions`, `.java-version`) are
read by status and env, but shells don't switch to them on their own yet: see [docs/project-files.md](docs/project-files.md).
`--json` on list, status, env, doctor, search, install, upgrade, use and remove prints one JSON document for editors and
scripts: see [docs/json.md](docs/json.md).
Sources: foojay, services.gradle.org, Maven Central, sbt GitHub releases. Index cached 24 h (`--refresh`, `--offline`).

## Not yet

pin/sync (applying project files), signed packages and an apt/dnf repository, init, prune, config command, self update,
Windows and Homebrew.

## Modules

| Module | Contents |
| --- | --- |
| `jkit-model` | ToolSpec, Version, Vendor, Platform; no I/O |
| `jkit-sources` | Http (+ cache), foojay, Gradle, Maven Central and sbt clients |
| `jkit-core` | Store, Installer, Archives, Config, ShellEnv |
| `jkit-platform` | OsServices: links, shell hooks (Unix, Windows) |
| `jkit-cli` | picocli commands and the native binary |

## License

MIT, see [LICENSE](LICENSE).
