# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately, not in a public issue: use **Report a vulnerability** on
the [Security tab](https://github.com/adriandeleon/jkit/security/advisories/new) of
adriandeleon/jkit (GitHub private vulnerability reporting). Include the jkit version
(`jkit --version`), your OS, and steps to reproduce. You should get a reply within a week; once a
fix is released, the advisory is published with credit to you unless you'd rather stay anonymous.

> Maintainer note: private vulnerability reporting must be switched on under
> Settings → Code security → Private vulnerability reporting for that link to work.

## Supported versions

Only the latest release gets security fixes. Upgrade before reporting if you can.

## What jkit verifies

jkit downloads JDKs, Maven, Gradle and sbt and runs them, so the trust it places in each download
matters:

- Downloads, checksums and version lists are fetched over HTTPS only. The one exception is a
  mirror on this machine (`JKIT_SOURCES_*_URL` pointing at `http://localhost`), which is reached
  directly, never through `HTTPS_PROXY`. Every redirect is checked before it is followed: HTTPS
  stays HTTPS, and plain HTTP only goes to this machine.
- When the source publishes a SHA-256, SHA-384 or SHA-512 checksum, jkit checks the download
  against it. A checksum file named for one algorithm (`.sha512`) must hold a hash of that kind.
  That checksum comes from the same source as the download (for example the vendor's release
  site or the foojay API), so it catches corruption and tampering in transit or on a mirror, not
  a compromised source.
- A download whose only checksum is SHA-1 (or MD5, where the source lists one) is installed but
  marked unverified. For Maven, jkit asks the repository for `.sha512` and, for releases older
  than 3.9.1, `.sha1`; a mirror that has only `.md5` counts as having no checksum.
- A download with no checksum at all is refused unless you pass `--allow-unverified`.
- Vendor signatures (GPG, Sigstore and the like) are not checked yet.

## Project files

jkit reads project files (`jkit.toml`, `.jkit.toml`, `.sdkmanrc`, `.tool-versions`,
`.java-version`) from the current directory and its parents, and they decide which JDK and tools
run. To keep another user from choosing them for you, jkit ignores a project file that is not
owned by you or by root, or that is world-writable, or that sits in such a directory; `jkit
status`, `jkit sync` and `jkit doctor` say when they ignored one. `jkit pin` and `jkit unpin`
refuse to write through a project file that is a symbolic link. Project files over 256 KiB are
refused. `JKIT_PROJECT_FILE` set to an absolute path is your own choice and is used as is.

## Untrusted network data

Version and vendor names from a source must be plain text (letters, digits, `.`, `+`, `_`, `~`,
`-`); anything else is skipped, so a source can't put a path or PATH separator into an install
folder name. Download links from a remote source must be https (an https link may still name
a host on your network: jkit only sends it a plain GET, and checks what comes back). Version lists and checksum
files are limited to 8 MB and two minutes (and JSON to a million tokens), downloads to 2 GB and
three hours (and given up on when they average under 32 KB/s; a request given up on for time or
speed isn't tried again), and an archive to 3 GB and 200,000 entries unpacked. Archives
are checked before they are unpacked: only ordinary files, folders, links, GNU long names and
PAX headers are accepted (no sparse files, devices or other entry types), names stay under
4,096 bytes, and symlink entries are read only that far. Archive entries can't escape their
folder, and files are never left writable by every user; jkit's own files and folders, and the
startup files `hook --install` creates, get explicit permissions whatever the umask.

## The jkit home

`~/.jkit` (or `JKIT_HOME`, which must be an absolute path) holds the programs jkit puts first on
`PATH`. jkit creates it readable only by you, and refuses to run when it, or its `tools` and
`global` folders, belong to another user (root is fine) or are world-writable, or when a folder
above it (followed through symbolic links) belongs to another user or is world-writable without
the sticky bit. This is checked on the first run too, before anything is created.

Limits: group-writable folders are accepted, because many distributions give every user a
private group and a umask that makes new folders group-writable. On Windows there are no POSIX
owners, so none of the ownership checks above apply there; rely on the default ACLs of your
user profile.

## Release artifacts

Each release lists SHA-256 checksums in `SHA256SUMS`, carries a GitHub build provenance
attestation (`gh attestation verify <file> --repo adriandeleon/jkit`) and a CycloneDX SBOM. The
macOS binaries are signed and notarized by Apple.
