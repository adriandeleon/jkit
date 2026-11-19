# jkit (prototype)

Installs and switches JDKs from several vendors, Maven and Gradle. Single native binary,
built with Java 25, picocli and GraalVM native-image. See the design spec for the full plan.

## Build

Maven, through the Maven Wrapper (Maven 3.9.16, checksum pinned in `.mvn/wrapper/maven-wrapper.properties`).

```bash
./mvnw verify                                          # compile + unit tests on JDK 25
GRAALVM_HOME=/path/to/graalvm-25 ./mvnw -Pnative package -DskipTests
# binary: jkit-cli/target/jkit
```

Building needs JDK 25; the native binary itself needs no JDK.

## Try it

```bash
export JKIT_HOME=/tmp/jkit-home          # keep tests away from ~/.jkit
eval "$(jkit hook bash)"                 # shell wrapper: lets `jkit use` change this shell
jkit search jdk
jkit install jdk@21                      # default vendor (temurin)
jkit install jdk@17 zulu
jkit use jdk@21 --global
jkit use jdk@17 zulu                     # this shell only
jkit install graalvm@25 && jkit use graalvm@25   # sets GRAALVM_HOME only
jkit list
jkit reset jdk
jkit remove jdk@17 zulu
```

## In the prototype

search, install, use (shell and `--global`), reset, list, remove, hook (bash/zsh/fish; pwsh untested).
Sources: foojay, services.gradle.org, Maven Central. Index cached 24 h (`--refresh`, `--offline`).

## Not yet

pin/sync and project files, status, env, doctor, init, prune, config command, self update,
Windows and macOS testing, CI and releases.

## Modules

| Module | Contents |
| --- | --- |
| `jkit-model` | ToolSpec, Version, Vendor, Platform; no I/O |
| `jkit-sources` | Http (+ cache), foojay, Gradle and Maven Central clients |
| `jkit-core` | Store, Installer, Archives, Config, ShellEnv |
| `jkit-platform` | OsServices: links, shell hooks (Unix, Windows) |
| `jkit-cli` | picocli commands and the native binary |
