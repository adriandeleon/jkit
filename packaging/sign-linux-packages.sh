#!/usr/bin/env bash
# Signs the .deb and .rpm files in each directory, in place, with the shared Linux release key:
# the one Editora signs its packages with and the Vitrina apt repository signs its index with,
# published at https://vitrina-project.dev/vitrina.asc and committed as packaging/linux/signing-key.asc.
# Adapted from Editora's scripts/release/sign-linux-packages.sh; a fix to the deb signing belongs in both.
#
# Both signatures are embedded, because each format's own tools check a different thing:
#   .rpm  rpmsign: `rpm -K`, and dnf or zypper once the key is imported (`rpm --import`).
#   .deb  an `_gpgorigin` member, which debsig-verify checks. apt and dpkg check nothing about a .deb
#         that was downloaded rather than fetched from a repository, and ignore the member.
# No detached .asc beside them: SHA256SUMS.asc, signed with jkit's own release key, covers every file.
#
# The deb is signed here rather than by debsigs, which does the same thing but knows only gzip
# members. The format is debsigs': a detached signature over the three members dpkg reads,
# concatenated in archive order, appended as a member named `_gpgorigin`.
#
# The key comes from the environment: LINUX_GPG_KEY, an ASCII-armoured secret key, and
# LINUX_GPG_PASSPHRASE if it has one. With LINUX_GPG_EXPECTED_KEY set to a public key file, the
# secret key must be that one: a release signed by any other key would verify against nothing.
#
# Needs gpg, ar and rpmsign. Usage: sign-linux-packages.sh <dir>...
set -euo pipefail

[ $# -gt 0 ] || { echo "usage: $0 <dir>..." >&2; exit 2; }
[ -n "${LINUX_GPG_KEY:-}" ] || { echo "::error::LINUX_GPG_KEY is not set"; exit 1; }

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GNUPGHOME"' EXIT

# Every gpg below, including the one rpmsign starts, reads this. Loopback lets the
# passphrase come from the file rather than from a pinentry no runner can answer.
{
    echo "batch"
    echo "no-tty"
    echo "pinentry-mode loopback"
} > "$GNUPGHOME/gpg.conf"
if [ -n "${LINUX_GPG_PASSPHRASE:-}" ]; then
    printf '%s' "$LINUX_GPG_PASSPHRASE" > "$GNUPGHOME/passphrase"
    echo "passphrase-file $GNUPGHOME/passphrase" >> "$GNUPGHOME/gpg.conf"
fi

printf '%s\n' "$LINUX_GPG_KEY" | gpg --quiet --import
fpr=$(gpg --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')
[ -n "$fpr" ] || { echo "::error::LINUX_GPG_KEY holds no secret key"; exit 1; }
echo "Signing with $fpr"

expected="${LINUX_GPG_EXPECTED_KEY:-}"
if [ -n "$expected" ]; then
    [ -f "$expected" ] || { echo "::error::no public key at $expected to check LINUX_GPG_KEY against"; exit 1; }
    want=$(gpg --with-colons --import-options show-only --import "$expected" |
        awk -F: '$1 == "fpr" { print $10; exit }')
    [ "$want" = "$fpr" ] || { echo "::error::LINUX_GPG_KEY is $fpr, but $expected is $want"; exit 1; }
fi

sign_deb() {
    local deb="$1" members=() member
    rm -rf "$GNUPGHOME/deb" && mkdir "$GNUPGHOME/deb"
    (cd "$GNUPGHOME/deb" && ar x "$deb")
    while read -r member; do
        case "$member" in
            debian-binary|control.tar*|data.tar*) members+=("$GNUPGHOME/deb/$member") ;;
            _gpgorigin) echo "::error::$(basename "$deb") is already signed"; exit 1 ;;
        esac
    done < <(ar t "$deb")
    [ "${#members[@]}" = 3 ] || { echo "::error::$(basename "$deb") does not have the three members of a deb"; exit 1; }
    cat "${members[@]}" | gpg --detach-sign --local-user "$fpr" --output "$GNUPGHOME/deb/_gpgorigin"
    ar q "$deb" "$GNUPGHOME/deb/_gpgorigin"
}

shopt -s nullglob
signed=0
for dir in "$@"; do
    # Absolute: the deb is unpacked from inside a directory of its own.
    dir="$(cd "$dir" && pwd)"
    for rpm in "$dir"/*.rpm; do
        # rpm before 4.19 names the key _gpg_name, from 4.19 _openpgp_sign_id; both are given.
        # __gpg: Ubuntu's rpm runs /usr/bin/gpg2, which only the gnupg2 transitional package installs.
        rpmsign --addsign --define "__gpg $(command -v gpg)" \
            --define "_gpg_path $GNUPGHOME" --define "_gpg_name $fpr" \
            --define "_openpgp_sign_id $fpr" "$rpm"
        echo "signed $(basename "$rpm")"
        signed=$((signed + 1))
    done
    for deb in "$dir"/*.deb; do
        sign_deb "$deb"
        echo "signed $(basename "$deb")"
        signed=$((signed + 1))
    done
done
[ "$signed" -gt 0 ] || { echo "::error::no .deb or .rpm to sign in $*"; exit 1; }
