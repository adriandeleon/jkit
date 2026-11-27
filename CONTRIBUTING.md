# Contributing

Bug reports, fixes and small features are welcome. For anything bigger, open an issue first so we
can agree on the approach before you write it.

## Build and test

Needs JDK 25 (`jkit install jdk@25`); Maven comes through the wrapper (its checksum is pinned in
`.mvn/wrapper/maven-wrapper.properties`), and the build stops early on an older JDK or Maven.

```bash
./mvnw verify                                          # compile + unit tests
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative package -DskipTests
# binary: jkit-cli/target/jkit
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative,linux-packages package -DskipTests
# jkit-cli/target/jkit_<version>_amd64.deb and jkit-<version>-<release>.x86_64.rpm
```

The `.deb` and `.rpm` are built in pure Java (jdeb, rpm-builder), for the build machine's
architecture.

CI runs `./mvnw verify` on Linux, macOS and Windows for every pull request. A pull request that
changes the build, the packaging or the native-image metadata also builds the native binaries; so
does every push to main.

To try a build without touching your own `~/.jkit`, give it a home of its own:

```bash
export JKIT_HOME="$(mktemp -d)"
jkit-cli/target/jkit install jdk@21
```

## Modules

| Module | Contents |
| --- | --- |
| `jkit-model` | ToolSpec, Version, Vendor, Platform; no I/O |
| `jkit-sources` | Http (+ cache), foojay, Gradle, Maven Central and sbt clients |
| `jkit-core` | Store, Installer, Archives, Config, ShellEnv, project files |
| `jkit-platform` | OsServices: links, shell hooks (Unix, Windows) |
| `jkit-cli` | picocli commands and the native binary |

Design notes for bigger features are in [docs/design/](docs/design/).

## Tests

- Tests must not use the network. Sources are tested against a server on `127.0.0.1` or with
  canned responses; the `JKIT_SOURCES_*_URL` variables accept `http://` to localhost for that.
- Tests must not touch your home directory, shell startup files or user environment: use a
  temporary `JKIT_HOME` and the test helpers that take an explicit environment.
- Something only the native binary can show (reflection, resources, the built-in keys) belongs in
  the smoke test in `.github/workflows/release.yml`.

## Dependencies

When dependencies change, update `packaging/third-party/components.tsv` and regenerate the notices
after a build:

```bash
packaging/third-party-notices.sh            # rewrites THIRD-PARTY-NOTICES
packaging/third-party-notices.sh --check    # what CI runs
```

GitHub Actions are pinned by commit SHA with the version in a comment, and workflows get
`permissions: contents: read` unless a job needs more.

## Pull requests

- One change per pull request, with tests.
- Commit messages: an imperative subject, and a body that says why.
- User-visible changes get a line in `CHANGELOG.md` under Unreleased.
- Keep jkit's output and docs plain: say what happened and what to do next.
- jkit itself is developed with Claude Code. AI-assisted pull requests are welcome; say so with a
  `Co-Authored-By:` line, and make sure you understand and have tested what you send.

## Releases

Pushing a `v*` tag publishes a release (see `.github/workflows/release.yml`). Afterwards, bump the
version in every `pom.xml` to the next `-SNAPSHOT` (after v0.5.0, `0.6.0-SNAPSHOT`): CI fails until
main's version is ahead of the latest release.

## Security issues

Don't open a public issue for a vulnerability; see [SECURITY.md](SECURITY.md).
