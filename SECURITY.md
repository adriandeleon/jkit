# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately, not in a public issue: use **Report a vulnerability** on
the [Security tab](https://github.com/adriandeleon/jkit/security/advisories/new) of
adriandeleon/jkit (GitHub private vulnerability reporting). Include the jkit version
(`jkit --version`), your OS, and steps to reproduce. You should get a reply within a week; once a
fix is released, the advisory is published with credit to you unless you'd rather stay anonymous.

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
- A download with no checksum at all is refused unless you pass `--allow-unverified`, or unless
  its publisher's signature checks out (Corretto, below).
- Some downloads are also checked against their publisher's detached OpenPGP signature, using
  keys built into jkit. Each key was taken from where the publisher's own documentation points and
  confirmed by verifying real downloads with it:

  | Downloads | Signature | Key (primary fingerprint) |
  | --- | --- | --- |
  | Temurin | listed by foojay | Adoptium `3B04 D753 C905 0D9A 5D34 3F39 843C 48A5 65F8 F04B` |
  | Microsoft Build of OpenJDK | listed by foojay | Microsoft `B602 4333 84B8 9913 0292 4D82 3553 1D31 5B21 C189` |
  | Amazon Corretto | `.sig` next to the download on corretto.aws | Amazon `6DC3 636D AE53 4049 C8B9 4623 A122 542A B04F 24E3` |
  | Apache Maven 3.9.0 and later | `.asc` next to the zip on Maven Central (or your mirror) | the release managers who signed them, from Maven's [KEYS](https://downloads.apache.org/maven/KEYS) file: Tamas Cservenak `29BE A2A6 45F2 D6CE D7FB 12E0 2B17 2E3E 1564 66E8` and `88BE 34F9 4BDB 2B53 5704 4E2E 3A38 7D43 9641 43E3`, Slawomir Jaranowski `8478 9D24 DF77 A324 33CE 1F07 9EB8 0E92 EB21 35B1`, Guillaume Nodet `EA23 DB13 60D9 0294 81E7 F2EF ECDF EA3C B449 3B94` |
  | sbt | `.asc` on sbt's GitHub releases | sbt build tool `2EE0 EA64 E40A 89B8 4B2D F734 99E8 2A75 642A C823` (its subkey `05B3 70DC 1261 4519 62FF E35A CD4E AA59 06F9 BF46` signs 1.12.2 on) |

  Keys are tied to what they sign: a Temurin download signed by Amazon's key, or a Maven zip signed
  by sbt's, doesn't count. Unlike a checksum, a signature shows who built the file, so it catches
  a compromised download source, mirror or foojay entry, not only tampering in transit.
  - A signature by the publisher's key that doesn't match the download stops the install.
  - A signature jkit can't fetch, read, or tie to the publisher's keys (a new key, a weak hash)
    also stops the install: the checksum usually comes from the same server as the download, so
    whoever can replace one can replace both, and a missing signature is exactly what they would
    serve. `--allow-unverified` installs it anyway, on the checksum alone, marked unverified, and
    says why. Expect this most from Maven: each release is signed by whichever Apache release
    manager cut it, so a release by someone new is refused until a jkit release adds their key
    (`jkit self-update`). A Maven mirror (`JKIT_SOURCES_MAVEN_URL`) that doesn't serve the `.asc`
    files is refused too. jkit pins the keys
    rather than fetching the KEYS file, because a file fetched at install time is only as
    trustworthy as the server it comes from.
  - Maven releases before 3.9.0 aren't checked: they were signed with SHA-1, DSA-1024 keys, or
    keys that have since expired. Other JDK vendors aren't checked (foojay lists no signatures for
    them), nor is Gradle, which publishes none.
  - Temurin and Microsoft builds are checked when foojay lists a signature for them, as it does
    for current builds; one foojay lists without a signature is checked against its checksum only.
  - Where there is a checksum, the signature is checked as well, never instead of it. Corretto
    publishes no per-version checksum (foojay lists none, and corretto.aws has only one for the
    latest build), so for Corretto the signature is the check: a good signature by Amazon's key
    installs it, counted as verified. Amazon's signature covers the file, not its name, so jkit
    also checks that the unpacked JDK's `release` file (`version.txt` for Corretto 8, which has no
    `release` file) reports the version it asked for.
- `jkit self-update` downloads jkit's own release archive over HTTPS from GitHub and checks it
  against that release's `SHA256SUMS`, then checks the new binary runs and reports the expected
  version before it replaces the old one. `SHA256SUMS` comes from the same place as the archive,
  so jkit first checks `SHA256SUMS.asc`, the release workflow's OpenPGP signature of it, against
  jkit's release key, built into jkit (`A944 4E8F 5048 FEBC B942 E110 FC89 EBE6 8C45 39E6`, in
  `jkit-core/src/main/resources/dev/jkit/core/keys/jkit-release.asc`):
  someone who could replace the files on the releases page couldn't sign them. A release without
  a good signature is refused. Releases before 0.4.0 have no `SHA256SUMS.asc`. `--check`, and the
  upgrade command printed for Homebrew, apt, dnf and winget, download nothing, so they don't need it. Each release also
  has a build attestation (`gh attestation verify <file> --repo adriandeleon/jkit`). A jkit
  installed with Homebrew, apt, dnf or winget is left to that tool. On Windows, the Visual C++
  runtime DLLs next to `jkit.exe` are replaced from the same checked archive; a folder without
  them doesn't get them.
- The Windows `.zip` carries Microsoft's `vcruntime140.dll` and `vcruntime140_1.dll`, which Windows
  loads before any system-wide copy. The release workflow takes them from the build machine's
  Visual Studio and checks they are signed by Microsoft. Windows Update doesn't patch a copy next to
  a program, so a fix to them reaches you with the next jkit release and `jkit self-update`; with
  the Visual C++ Redistributable installed, deleting the two DLLs leaves the runtime to Windows Update.

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
speed isn't tried again), and an archive to 3 GB and 50,000 entries unpacked. Archives
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
