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

- Downloads and metadata are fetched over HTTPS only.
- When the source publishes a SHA-256 or SHA-512 checksum, jkit checks the download against it.
  That checksum comes from the same source as the download (for example the vendor's release
  site or the foojay API), so it catches corruption and tampering in transit or on a mirror, not
  a compromised source.
- A download whose only checksum is MD5 or SHA-1 is installed but marked unverified.
- A download with no checksum at all is refused unless you pass `--allow-unverified`.
- Vendor signatures (GPG, Sigstore and the like) are not checked yet.

## Project files

jkit reads project files (`jkit.toml`, `.jkit.toml`, `.sdkmanrc`, `.tool-versions`,
`.java-version`) from the current directory and its parents, and they decide which JDK and tools
run. To keep another user from choosing them for you, jkit ignores a project file in a directory
that is not owned by you or by root, or that is world-writable.

## Release artifacts

Each release lists SHA-256 checksums in `SHA256SUMS`, carries a GitHub build provenance
attestation (`gh attestation verify <file> --repo adriandeleon/jkit`) and a CycloneDX SBOM. The
macOS binaries are signed and notarized by Apple.
