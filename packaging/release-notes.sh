#!/usr/bin/env bash
# Prints the notes for one release: the CHANGELOG.md section for VERSION, then how to install it.
#
#   packaging/release-notes.sh VERSION [CHANGELOG.md]   # the notes, on stdout
#   packaging/release-notes.sh --check [CHANGELOG.md]   # every released section has notes (CI)
#
# The release workflow uses it for the GitHub release's text. With no section for VERSION (a
# pre-release, say) it prints nothing and exits 3, and the workflow falls back to GitHub's
# generated notes.
set -euo pipefail

usage="usage: release-notes.sh VERSION [CHANGELOG.md] | --check [CHANGELOG.md]"
here=$(cd "$(dirname "$0")" && pwd)

# The lines between "## [VERSION]" and the next "## [" (or the link list at the end), with the
# blank lines around them trimmed
section() {
  awk -v v="$1" '
    index($0, "## [" v "]") == 1 { on = 1; next }
    on && (/^## \[/ || /^\[[^]]+\]: /) { exit }
    on { lines[++n] = $0 }
    END {
      first = 1; while (first <= n && lines[first] ~ /^[[:space:]]*$/) first++
      last = n;  while (last >= first && lines[last] ~ /^[[:space:]]*$/) last--
      for (i = first; i <= last; i++) print lines[i]
    }' "$2"
}

# GitHub shows each newline in a release's text as a line break, so the hard-wrapped lines of a
# paragraph or list item are joined into one (not inside code blocks, tables or before a new item)
unwrap() {
  awk '
    /^```/ { flush(); print; code = !code; next }
    code { print; next }
    /^[[:space:]]*$/ { flush(); print; next }
    /^(#|\||[-*+] |[0-9]+\. |> )/ { flush(); buf = $0; next }
    { line = $0; sub(/^[[:space:]]+/, "", line); buf = (buf == "" ? $0 : buf " " line) }
    END { flush() }
    function flush() { if (buf != "") print buf; buf = "" }'
}

if [ "${1:-}" = --check ]; then
  changelog=${2:-$here/../CHANGELOG.md}
  versions=$(sed -n 's/^## \[\([0-9][^]]*\)\].*/\1/p' "$changelog")
  [ -n "$versions" ] || { echo "no released versions in $changelog" >&2; exit 1; }
  status=0
  for v in $versions; do
    if ! [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
      echo "$changelog: '## [$v]' is not a release version" >&2; status=1; continue
    fi
    if [ -z "$(section "$v" "$changelog")" ]; then
      echo "$changelog: the [$v] section is empty" >&2; status=1
    fi
    if ! grep -q "^\[$v\]: " "$changelog"; then
      echo "$changelog: no [$v]: link at the end" >&2; status=1
    fi
  done
  [ "$status" -eq 0 ] && echo "$(printf '%s\n' "$versions" | wc -l | tr -d ' ') release sections have notes"
  exit "$status"
fi

version=${1:?$usage}
changelog=${2:-$here/../CHANGELOG.md}
# a release version: 1.2.3 or 1.2.3-rc.1, nothing awk or the notes could be tricked by
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "not a release version: '$version' (expected 1.2.3 or 1.2.3-rc.1)" >&2
  exit 1
fi
[ -r "$changelog" ] || { echo "can't read $changelog" >&2; exit 1; }
notes=$(section "$version" "$changelog")
if [ -z "$notes" ]; then
  echo "no [$version] section in $changelog" >&2
  exit 3
fi
repo=${GITHUB_REPOSITORY:-adriandeleon/jkit}

{
printf '%s\n\n## Install\n\n' "$notes"
case "$version" in
  *-*)
    cat <<EOF
A pre-release: Homebrew, apt and winget skip it. Download the \`.tar.gz\`, \`.zip\`, \`.deb\`,
\`.rpm\` or \`.msi\` for your platform below.
EOF
    ;;
  *)
    cat <<EOF
| Platform | |
| --- | --- |
| macOS, Linux | \`brew install adriandeleon/tap/jkit\` (\`brew upgrade jkit\` to update) |
| Debian, Ubuntu | \`sudo apt install jkit\` from the [Vitrina](https://vitrina-project.dev) apt repository ([setup](https://jkit-project.dev/install/#apt)), or the \`.deb\` below |
| Fedora, RHEL, Rocky, Alma 9+ | the \`.rpm\` below: \`sudo dnf install ./jkit-*.x86_64.rpm\` (\`.aarch64.rpm\` on ARM) |
| Windows x64 (preview) | the \`.msi\` below, just for you or for all users; or \`winget install AdrianDeLeon.jkit\` (once winget-pkgs has merged this release) |
| Alpine and other musl Linux (x64) | the \`-linux-x64-musl\` \`.tar.gz\` below |
| Anything else | a \`.tar.gz\` or \`.zip\` below |

A jkit from a \`.tar.gz\` or \`.zip\` updates itself with \`jkit self-update\`; for the others,
\`jkit self-update\` prints the command that updates it.
EOF
    ;;
esac
cat <<EOF

To check a download: first \`gpg --verify SHA256SUMS.asc SHA256SUMS\` (jkit's release key is in
[SECURITY.md](https://github.com/$repo/blob/v$version/SECURITY.md)), then
\`sha256sum -c SHA256SUMS --ignore-missing\`, and \`gh attestation verify <file> --repo $repo\`. Requirements: [Install](https://github.com/$repo/blob/v$version/README.md#install).
EOF
} | unwrap
