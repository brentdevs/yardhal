#!/usr/bin/env bash
set -euo pipefail

SDK_TEMPLATE="${YARDHAL_SDK_TEMPLATE:?YARDHAL_SDK_TEMPLATE is not set}"
TARGET="${YARDHAL_SDK_CLONE:-$HOME/.cache/yardhal/android-sdk}"

stamp_file="$TARGET/.template-stamp"
current_stamp=""
if [ -f "$stamp_file" ]; then
  current_stamp="$(cat "$stamp_file")"
fi

if [ "$current_stamp" = "$SDK_TEMPLATE" ]; then
  exit 0
fi

echo "Provisioning writable Android SDK clone at $TARGET ..."
rm -rf "$TARGET"
mkdir -p "$(dirname "$TARGET")"
cp -rL "$SDK_TEMPLATE" "$TARGET"
chmod -R u+w "$TARGET"
printf '%s' "$SDK_TEMPLATE" > "$stamp_file"
echo "Android SDK ready."
