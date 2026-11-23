#!/bin/sh
# Writes the Debian changelog for the .deb: one entry for this version, pointing at the release
# notes. jkit is a native package (no Debian revision), so Debian policy wants it at
# /usr/share/doc/jkit/changelog.gz, compressed with gzip -9n.
#
#   packaging/deb-changelog.sh <version> <maintainer> <output.gz>
#
# The date is the last commit's, so the same commit always gives the same file.
set -eu
[ $# -eq 3 ] || { echo "usage: $0 <version> <maintainer> <output.gz>" >&2; exit 2; }
version=$1
maintainer=$2
out=$3
case "$version" in
  *[!0-9A-Za-z.+~-]*|'') echo "$0: '$version' is not a Debian version" >&2; exit 2 ;;
esac
epoch=$(git -C "$(dirname "$0")" log -1 --format=%ct 2>/dev/null || date +%s)
date=$(LC_ALL=C date -u -R -d "@$epoch" 2>/dev/null || LC_ALL=C date -u -r "$epoch" '+%a, %d %b %Y %H:%M:%S +0000')
tag=v$(printf '%s' "$version" | tr '~' '-')
mkdir -p "$(dirname "$out")"
{
  printf 'jkit (%s) unstable; urgency=medium\n\n' "$version"
  printf '  * Release %s. Changes: https://github.com/adriandeleon/jkit/releases/tag/%s\n\n' "$version" "$tag"
  printf ' -- %s  %s\n' "$maintainer" "$date"
} | gzip -9n > "$out"
