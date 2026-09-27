#!/usr/bin/env bash
set -euo pipefail

VERSION="2.14.0"
SHA256="333f2f119f0f80c94b48f7c8894f82a1bfc3aeaffd58a98d9e0aa70f848e4209"
URL="https://github.com/ergochat/ergo/releases/download/v${VERSION}/ergo-${VERSION}-linux-x86_64.tar.gz"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_DIR="${YARDHAL_TOOLS_DIR:-$ROOT/.tools}"
ERGO_DIR="$TOOLS_DIR/ergo"
STAMP="$ERGO_DIR/.version"

if [ -x "$ERGO_DIR/ergo" ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION" ]; then
  exit 0
fi

mkdir -p "$TOOLS_DIR"
ARCHIVE="$TOOLS_DIR/ergo-${VERSION}.tar.gz"

echo "Downloading Ergo ${VERSION} ..."
curl -fsSL "$URL" -o "$ARCHIVE"
echo "${SHA256}  ${ARCHIVE}" | sha256sum -c - > /dev/null

rm -rf "$ERGO_DIR"
mkdir -p "$ERGO_DIR"
tar -xzf "$ARCHIVE" -C "$ERGO_DIR" --strip-components=1
rm -f "$ARCHIVE"
printf '%s' "$VERSION" > "$STAMP"
"$ERGO_DIR/ergo" --version
