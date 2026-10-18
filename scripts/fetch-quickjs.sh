#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=2024-01-13
URL="https://bellard.org/quickjs/quickjs-${VERSION}.tar.xz"
SHA256=3c4bf8f895bfa54beb486c8d1218112771ecfc5ac3be1036851ef41568212e03
DEST="$ROOT/vendor/quickjs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl --fail --location --silent --show-error "$URL" -o "$TMP/quickjs.tar.xz"
echo "$SHA256  $TMP/quickjs.tar.xz" | sha256sum --check --status || { echo 'QuickJS archive SHA-256 mismatch' >&2; exit 1; }
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xJf "$TMP/quickjs.tar.xz" --strip-components=1 -C "$DEST"
echo "QuickJS $VERSION verified and unpacked at $DEST"
