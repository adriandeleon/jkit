#!/usr/bin/env bash
# Writes the winget manifests for one release: winget-manifests.sh VERSION SHA256SUMS OUTDIR
# The release workflow submits OUTDIR to microsoft/winget-pkgs with wingetcreate.
#
# The package is a zip holding jkit.exe (with LICENSE and THIRD-PARTY-NOTICES beside it), installed
# as a portable command (no installer, no admin): winget unpacks it and puts `jkit` on the PATH.
# Windows on ARM installs the x64 build, which runs under emulation; GraalVM has no Windows ARM
# native-image yet.
set -euo pipefail

version=${1:?usage: winget-manifests.sh VERSION SHA256SUMS OUTDIR}
sums=${2:?usage: winget-manifests.sh VERSION SHA256SUMS OUTDIR}
out=${3:?usage: winget-manifests.sh VERSION SHA256SUMS OUTDIR}

# a release version: 1.2.3 or 1.2.3-rc.1, nothing a formula or manifest could be tricked by
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "not a release version: '$version' (expected 1.2.3 or 1.2.3-rc.1)" >&2
  exit 1
fi
repo=${GITHUB_REPOSITORY:-adriandeleon/jkit}
id=AdrianDeLeon.jkit
schema=1.10.0

file="jkit-$version-windows-x64.zip"
sha=$(awk -v f="$file" '$2 == f || $2 == "*" f { print toupper($1) }' "$sums")
[ -n "$sha" ] || { echo "no checksum for $file in $sums" >&2; exit 1; }

mkdir -p "$out"

cat > "$out/$id.yaml" <<EOF
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.version.$schema.schema.json
PackageIdentifier: $id
PackageVersion: $version
DefaultLocale: en-US
ManifestType: version
ManifestVersion: $schema
EOF

cat > "$out/$id.installer.yaml" <<EOF
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.installer.$schema.schema.json
PackageIdentifier: $id
PackageVersion: $version
InstallerType: zip
NestedInstallerType: portable
NestedInstallerFiles:
- RelativeFilePath: jkit.exe
  PortableCommandAlias: jkit
Commands:
- jkit
ReleaseDate: $(date -u +%Y-%m-%d)
# jkit.exe (a GraalVM native image) links the Visual C++ runtime: VCRUNTIME140.dll, VCRUNTIME140_1.dll
Dependencies:
  PackageDependencies:
  - PackageIdentifier: Microsoft.VCRedist.2015+.x64
Installers:
- Architecture: x64
  InstallerUrl: https://github.com/$repo/releases/download/v$version/$file
  InstallerSha256: $sha
ManifestType: installer
ManifestVersion: $schema
EOF

cat > "$out/$id.locale.en-US.yaml" <<EOF
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.defaultLocale.$schema.schema.json
PackageIdentifier: $id
PackageVersion: $version
PackageLocale: en-US
Publisher: Adrian De Leon
PublisherUrl: https://github.com/adriandeleon
PublisherSupportUrl: https://github.com/$repo/issues
PackageName: jkit
PackageUrl: https://jkit-project.dev
License: MIT (bundled components under Apache-2.0, BSD-2-Clause, BSD-3-Clause, BSL-1.0, CC0-1.0, ICU, MPL-2.0, Unicode-3.0, W3C, Zlib, GPL-2.0 with the Classpath Exception and the CUP and Cryptix licenses; see THIRD-PARTY-NOTICES)
LicenseUrl: https://github.com/$repo/blob/v$version/LICENSE
ShortDescription: Installs and switches JDKs from several vendors, Maven, Gradle and sbt.
Description: A single native binary that installs and switches JDKs from several vendors, Maven, Gradle and sbt, per shell, per project or globally.
Moniker: jkit
Tags:
- java
- jdk
- maven
- gradle
- sbt
- version-manager
ReleaseNotesUrl: https://github.com/$repo/releases/tag/v$version
ManifestType: defaultLocale
ManifestVersion: $schema
EOF
