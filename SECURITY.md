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
  | Temurin 8u342, 11.0.16, 17.0.4, 18.0.2, 19 and later | `.sig` next to the download on Adoptium's GitHub releases | Adoptium `3B04 D753 C905 0D9A 5D34 3F39 843C 48A5 65F8 F04B` |
  | Microsoft Build of OpenJDK | `.sig` next to the download on aka.ms | Microsoft `B602 4333 84B8 9913 0292 4D82 3553 1D31 5B21 C189` |
  | Amazon Corretto 8u242, 11.0.6 and later (January 2020 on) | `.sig` next to the download on corretto.aws | Amazon `6DC3 636D AE53 4049 C8B9 4623 A122 542A B04F 24E3` |
  | Apache Maven 3.9.0 and later | `.asc` next to the zip on Maven Central (or your mirror) | the release managers who signed them, from Maven's [KEYS](https://downloads.apache.org/maven/KEYS) file: Tamas Cservenak `29BE A2A6 45F2 D6CE D7FB 12E0 2B17 2E3E 1564 66E8` and `88BE 34F9 4BDB 2B53 5704 4E2E 3A38 7D43 9641 43E3`, Slawomir Jaranowski `8478 9D24 DF77 A324 33CE 1F07 9EB8 0E92 EB21 35B1`, Guillaume Nodet `EA23 DB13 60D9 0294 81E7 F2EF ECDF EA3C B449 3B94` |
  | sbt | `.asc` on sbt's GitHub releases | sbt build tool `2EE0 EA64 E40A 89B8 4B2D F734 99E8 2A75 642A C823` (its subkey `05B3 70DC 1261 4519 62FF E35A CD4E AA59 06F9 BF46` signs 1.12.2 on) |

  Keys are tied to what they sign: a Temurin download signed by Amazon's key, or a Maven zip signed
  by sbt's, doesn't count. Unlike a checksum, a signature shows who built the file, so it catches
  a compromised download source, mirror or foojay entry, not only tampering in transit. Only a key
  that may sign counts: its self-signature (for a subkey, its binding signature) must mark it for
  signing, it must be RSA of at least 2048 bits, EdDSA or ECDSA, and it must not have expired when
  it signed. So Guillaume Nodet's DSA-1024 primary key, which is in Maven's key file and signed his
  releases before 3.9.0, isn't accepted; his signing subkeys are.
  - A signature by the publisher's key that doesn't match the download stops the install.
  - A signature jkit can't fetch, read, or tie to the publisher's keys (a new key, a weak hash, a key
    not marked for signing, a DSA or short RSA key, a key that had expired when it signed)
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
    keys that have since expired. Temurin builds from before Adoptium started signing (July 2022)
    have no signature either. jkit installs these on their checksum and says only the checksum was
    checked. Other JDK vendors aren't checked (they publish no signatures, or none with a key jkit
    has), nor is Gradle, which publishes none.
  - Amazon signed its 2019 Corretto builds (8u222, 8u232, 11.0.4, 11.0.5), which foojay still
    lists, each with a key of its own, since retired (SHA-1 signatures, keys that expired in
    2024), and they have no checksum: jkit says so, and installs one only with
    `--allow-unverified`. `jkit search` marks them ○, as it does builds with nothing to check.
  - JDK builds are found through the foojay API, and jkit trusts it as little as it can. It
    doesn't rely on foojay to say a Temurin, Microsoft or Corretto build is signed: for every
    build these vendors sign, it looks for the `.sig` next to the download and doesn't read the
    signature link foojay lists, if any (foojay leaves it out for some builds that have one). For a
    Temurin build from before Adoptium signed, a signature link foojay lists is used if it is
    HTTPS; over plain http it is refused, not ignored. It takes only builds foojay lists under the vendor and major version you asked for,
    so an answer can't call a Temurin build "Zulu", or a Temurin 21 build 17.0.3, to have it
    installed without the signature check. Vendor names are checked against foojay's list of
    distributions, but foojay's synonyms can only add names: Temurin (and `adoptium`), Microsoft
    and Corretto are known without asking foojay, a distribution's own name or a name jkit reads
    as an alias always means that vendor, and a synonym two distributions claim is ignored. And a
    build of these three vendors, or of Zulu, Liberica, SapMachine, Semeru, Oracle, GraalVM CE,
    Oracle GraalVM or Mandrel, must download from the vendor's own server, at a link of the vendor's own shape ending in the build's file name,
    or it is refused: a release asset of Adoptium's `temurin<N>-binaries` repositories
    (`github.com/adoptium/temurin<N>-binaries/releases/download/<tag>/<file>`; GitHub serves a
    fork's commits from the original repository's other paths), `aka.ms/download-jdk/<file>`,
    `corretto.aws/downloads/resources/<version>/<file>` (or `d3pxv6yz143wms.cloudfront.net/<version>/<file>`,
    where Amazon still serves its 2019 builds), `cdn.azul.com/zulu/bin/<file>` (or
    `zulu-embedded/bin/`), a release asset of `bell-sw/Liberica`, `SAP/SapMachine`,
    `ibmruntimes/semeru<N>-binaries` (or `-certified-binaries`), `graalvm/graalvm-ce-builds` or
    `graalvm/mandrel` on GitHub, `download.oracle.com/java/` and `/graalvm/`, and Oracle's
    `gds.oracle.com/api/<date>/artifacts/<id>/content`. That is where foojay lists every one of them; jkit checks the address foojay
    names, and the redirects the vendor's server answers with are checked only to stay on HTTPS.
    A foojay mirror of your own (`JKIT_SOURCES_FOOJAY_URL`) may serve the builds these vendors
    signed itself, with each `.sig` next to its file: the signature, which is still required, and
    the version check after it are what make those safe, not the host. It must keep the vendor's
    links for the builds they didn't sign (Temurin before July 2022, and every build of the other vendors above). A mirror on this machine
    (`http://localhost`) may serve those too.
  - What that leaves a compromised foojay: it can choose which build of the vendor and major you
    asked for is installed, an older one included (with no version given, also which major), and
    for a Temurin build from before July 2022 it supplies the checksum. Such a build still comes
    from Adoptium's GitHub releases, but only its checksum is checked, and jkit can't tell which
    of Adoptium's builds it is. For a signed build it can only make the install fail. For Zulu,
    Liberica, SapMachine, Semeru, Oracle, GraalVM CE, Oracle GraalVM and Mandrel it also supplies
    the checksum, but the file still comes from the vendor's server. For other vendors (Kona,
    Dragonwell, JetBrains, Liberica NIK, jdk.java.net's OpenJDK and the rest) it chooses the link
    and the checksum: install those only if you trust foojay as much as the vendor.
  - When a lookup fails (the server is down, answers 5xx or 429, or a captive portal answers),
    jkit uses its cached answer only if it is at most 7 days old; an older one fails, and
    `--offline` uses it on purpose. So a network that only blocks foojay can't keep jkit on an
    old version list for long.
  - Where there is a checksum, the signature is checked as well, never instead of it. Corretto
    publishes no per-version checksum (foojay lists none, and corretto.aws has only one for the
    latest build), so for Corretto the signature is the check: a good signature by Amazon's key
    installs it, counted as verified.
  - A signature covers the file, not its name: any build the publisher signed would pass, an
    older one included. So after a good signature jkit also checks that the unpacked tool is the
    version it asked for: a JDK's `release` file, every part of the version (`version.txt` for
    Corretto 8, which has no `release` file), Maven's `lib/maven-core-<version>.jar`, and the
    version sbt's launcher (`bin/sbt-launch.jar`) starts by default.
  - A signature is read only up to 64 KB; a link that serves more is cut off there.
- `jkit self-update` downloads jkit's own release archive over HTTPS from GitHub and checks it
  against that release's `SHA256SUMS`, then checks the new binary runs and reports the expected
  version before it replaces the old one. `SHA256SUMS` comes from the same place as the archive,
  so jkit first checks `SHA256SUMS.asc`, the release workflow's OpenPGP signature of it, against
  jkit's release key, built into jkit (`A944 4E8F 5048 FEBC B942 E110 FC89 EBE6 8C45 39E6`, in
  `jkit-core/src/main/resources/dev/jkit/core/keys/jkit-release.asc`):
  someone who could replace the files on the releases page couldn't sign them. A release without
  a good signature is refused. Releases before 0.4.0 have no `SHA256SUMS.asc`. The command
  printed for Homebrew, the apt repository and winget downloads nothing, so it doesn't need it. For
  an `.rpm` or a `.deb` installed from a file (dnf and apt don't check those packages'
  signatures), self-update and `--check` check `SHA256SUMS.asc` first and print a command that
  downloads the package into a new `mktemp -d` folder and installs it only if `sha256sum -c`
  matches the SHA-256 the signed `SHA256SUMS` lists. A pre-release replaces only a pre-release,
  even if it is marked as the latest release. Each release also
  has a build attestation (`gh attestation verify <file> --repo adriandeleon/jkit`). A jkit
  installed with Homebrew, apt, dnf or winget is left to that tool. For one from the Windows `.msi`,
  self-update checks `SHA256SUMS.asc` first and prints a PowerShell command that downloads the
  newer `.msi` into your `%TEMP%` and runs it only if `Get-FileHash` matches the SHA-256 the
  signed `SHA256SUMS` lists. On Windows, the Visual C++
  runtime DLLs next to `jkit.exe` are replaced from the same checked archive; a folder without
  them doesn't get them.
- From 0.5.0, the `.deb` and `.rpm` are signed with the Linux release key the
  Vitrina apt repository signs its index with (`8486 A7AF 4FC8 FEE9 3D83 CDB4 EA5F A138 C2C0 9C4A`,
  in `packaging/linux/signing-key.asc` and at https://vitrina-project.dev/vitrina.asc), so apt
  and dnf users trust one key. After `sudo rpm --import https://vitrina-project.dev/vitrina.asc`,
  `rpm -K` checks an `.rpm`; `dnf` checks it when it installs from a repository, but by default
  not a package given as a file or URL (`localpkg_gpgcheck`). The `.deb`'s signature is a
  `_gpgorigin` member, which `debsig-verify` checks; apt and dpkg don't check a downloaded `.deb`.
- From 0.5.0, the Windows `.zip` carries Microsoft's `vcruntime140.dll` and `vcruntime140_1.dll`, which Windows
  loads before any system-wide copy. The release workflow takes them from the build machine's
  Visual Studio and checks they are signed by Microsoft. Windows Update doesn't patch a copy next to
  a program, so a fix to them reaches you with the next jkit release and `jkit self-update`; with
  the Visual C++ Redistributable installed, deleting the two DLLs leaves the runtime to Windows Update.
  The `.msi` (from 0.6.0) installs the same two DLLs next to `jkit.exe`, and a newer `.msi` replaces them.
- jkit's Windows files, `jkit.exe` and the `.msi`, aren't Authenticode-signed, so Windows
  SmartScreen may warn when you open a downloaded `.msi` ("Windows protected your PC"). Check the
  `.msi` against `SHA256SUMS` (below) before choosing "Run anyway".

## Project files

jkit reads project files (`jkit.toml`, `.jkit.toml`, `.sdkmanrc`, `.tool-versions`,
`.java-version`) from the current directory and its parents, and they decide which JDK and tools
run. To keep another user from choosing them for you, jkit ignores a project file that is not
owned by you or by root, or that is world-writable, or that sits in such a directory; `jkit
status`, `jkit sync` and `jkit doctor` say when they ignored one. `jkit pin` and `jkit unpin`
refuse to write through a project file that is a symbolic link. Project files over 256 KiB are
refused. `JKIT_PROJECT_FILE` set to an absolute path is your own choice and is used as is.
On Windows there are no POSIX owners or permissions to check, so these ownership and
world-writable checks don't apply there: a project file in a shared folder or on a network share
is read like your own.

A project file you do own, such as one in a repository you cloned, chooses what `jkit sync`
installs: any vendor and version jkit knows, including old releases with known vulnerabilities
and builds whose only published checksum is SHA-1 (installed marked unverified). It can't choose
a download URL, a mirror or any other setting, and `cd` with auto-switch only switches between
builds already installed; it never downloads. Read a cloned repository's project file before you
run `jkit sync` in it, as you would its build script.

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
folder, and what they unpack is never left writable by anyone but you (not by your group either,
whatever the archive or the umask says); jkit's own files and folders, and the
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
attestation and a CycloneDX SBOM. From 0.4.0, `SHA256SUMS.asc` is an OpenPGP signature of
`SHA256SUMS` by jkit's release key. The macOS binaries are signed and notarized by Apple, and from
0.5.0 the `.deb` and `.rpm` are signed too (see above).

### Verifying a download

Put the archive you downloaded, `SHA256SUMS` and `SHA256SUMS.asc` from the same release in one
folder (for example `gh release download v0.4.0 --repo adriandeleon/jkit -p SHA256SUMS -p
SHA256SUMS.asc -p 'jkit-0.4.0-linux-x64.tar.gz'`), and run these in that folder.

1. Import jkit's release key, once:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/adriandeleon/jkit/main/jkit-core/src/main/resources/dev/jkit/core/keys/jkit-release.asc | gpg --import
   ```

2. Check that `SHA256SUMS` is signed by it:

   ```bash
   gpg --verify SHA256SUMS.asc SHA256SUMS
   ```

   It must say `Good signature from "jkit release signing key"`, and the key it was made with must
   be `A9444E8F5048FEBCB942E110FC89EBE68C4539E6`. gpg also warns that the key "is not certified
   with a trusted signature": that only means you haven't signed the key yourself. Comparing the
   fingerprint with the one here is the check.

3. Check the archive against `SHA256SUMS`:

   ```bash
   sha256sum -c SHA256SUMS --ignore-missing
   ```

   It must print `<file>: OK`. On macOS, use `shasum -a 256 -c SHA256SUMS --ignore-missing`. On
   Windows PowerShell, compare `(Get-FileHash <file>).Hash` with the file's line in `SHA256SUMS`.

Without gpg, the build attestation shows that a file was built by this repository's release
workflow:

```bash
gh attestation verify jkit-0.4.0-linux-x64.tar.gz --repo adriandeleon/jkit
```

`jkit self-update` does steps 2 and 3 itself, with the key built into jkit.

### Verifying a `.deb` or `.rpm`

From 0.5.0, the `.deb` and `.rpm` are signed with the Vitrina repository's key (see
[What jkit verifies](#what-jkit-verifies)). To check an `.rpm`:

```bash
sudo rpm --import https://vitrina-project.dev/vitrina.asc
rpm -K jkit-*.rpm                           # digests signatures OK
```

`dnf` checks it the same way when it installs one from a repository once the key is imported,
but by default not a package given as a file or URL (`localpkg_gpgcheck=0`), so run `rpm -K`
first. A `.deb` carries its
signature as a `_gpgorigin` member, which `debsig-verify` checks; apt and dpkg don't check a
downloaded `.deb`. Both are also listed in `SHA256SUMS`, so the steps above work for them too.

### The SBOM

Each release's CycloneDX SBOM is made from the Linux x64 build. The other builds compile in the
same libraries; the macOS x64 binary is built with Liberica NIK instead of GraalVM CE.
