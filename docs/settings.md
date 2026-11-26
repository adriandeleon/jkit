# Settings and environment variables

## `config.toml`

`~/.jkit/config.toml` (or `$JKIT_HOME/config.toml`):

```toml
[jdk]
vendor = "temurin"        # default JDK vendor
[graalvm]
vendor = "graalvm-ce"     # default GraalVM vendor
[shell]
auto_switch = true        # switch to a project's versions on cd (default false; jkit hook --install --auto-switch)
```

`jkit config` lists each setting, its value and where it comes from (an environment variable,
`config.toml` or the default). `jkit config set jdk.vendor zulu` and `jkit config unset
jdk.vendor` change the file, keeping its comments. An environment variable wins over the file.

## Environment variables

| Variable | What |
| --- | --- |
| `JKIT_HOME` | where jkit keeps everything (default `~/.jkit`); an absolute path to a folder you (or root) own that other users can't write to |
| `JKIT_JDK_VENDOR` | like `jdk.vendor` |
| `JKIT_GRAALVM_VENDOR` | like `graalvm.vendor` |
| `JKIT_AUTO_SWITCH` | like `shell.auto_switch` (`1`/`0`); `0` also stops switching in a shell that's already open |
| `JKIT_DEBUG` | set (to anything): when a command fails with an error, also print its stack trace on stderr. For reporting a bug |
| `JKIT_PROJECT_FILE` | use this project file, or none when empty ([project files](project-files.md)) |
| `JKIT_NETWORK_OFFLINE` | `1`/`true`: like `--offline`, version lists come only from the cache |
| `HTTPS_PROXY`, `NO_PROXY` | the proxy to use, and the hosts to reach directly |
| `JKIT_NETWORK_PROXY` | the proxy, taking precedence over `HTTPS_PROXY` |
| `JKIT_SOURCES_FOOJAY_URL`, `JKIT_SOURCES_GRADLE_URL`, `JKIT_SOURCES_MAVEN_URL`, `JKIT_SOURCES_SBT_URL`, `JKIT_SOURCES_RELEASES_URL` | https mirrors of foojay, services.gradle.org, Maven Central, sbt's releases and jkit's own releases. What a mirror must keep is in [SECURITY.md](../SECURITY.md#what-jkit-verifies) |
