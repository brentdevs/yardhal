#!/usr/bin/env bash
set -euo pipefail

AVD_NAME="${YARDHAL_AVD_NAME:-yardhal-test}"
BOOT_TIMEOUT="${YARDHAL_BOOT_TIMEOUT:-300}"

EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$EMULATOR" ]; then
  echo "emulator binary missing from ANDROID_HOME — re-provision SDK" >&2
  exit 1
fi

export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"

if [ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
  echo "Creating AVD $AVD_NAME ..."
  IMAGE="$("$SDKMANAGER" --list_installed 2>/dev/null | grep -o 'system-images;android-35;google_apis;x86_64' | head -1 || true)"
  if [ -z "$IMAGE" ]; then
    echo "system image not installed" >&2
    exit 1
  fi
  echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" --device pixel_6 --force
fi

echo "Booting emulator (headless) ..."
"$EMULATOR" -avd "$AVD_NAME" \
  -no-window \
  -no-boot-anim \
  -no-snapshot \
  -gpu swiftshader_indirect \
  -no-audio \
  > /tmp/opencode/emulator.log 2>&1 &
EMU_PID=$!
echo $EMU_PID > /tmp/opencode/emulator.pid

cleanup() {
  kill "$EMU_PID" 2>/dev/null || true
}
trap cleanup EXIT

"$ADB" wait-for-device
echo "device detected, waiting for boot completion ..."
for i in $(seq 1 "$BOOT_TIMEOUT"); do
  BOOTED="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$BOOTED" = "1" ]; then
    echo "BOOT_COMPLETE after ${i}s"
    exit 0
  fi
  if ! kill -0 "$EMU_PID" 2>/dev/null; then
    echo "emulator died — see /tmp/opencode/emulator.log" >&2
    exit 1
  fi
  sleep 1
done
echo "boot timed out after ${BOOT_TIMEOUT}s" >&2
exit 1
