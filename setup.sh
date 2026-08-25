#!/usr/bin/env bash
# One-shot setup for DashVoice on a connected BYD head unit.
#
# Does three things:
#   1. installs the APK
#   2. pushes the Vosk English model (only if not already there)
#   3. binds the accessibility service
#
# The accessibility binding needs a specific sequence on this firmware:
# writing enabled_accessibility_services while accessibility_enabled is
# already 1 does NOT bind the service. It has to go 0 -> write list -> 1.
#
# Re-run this after every reinstall, because replacing the APK kills the
# accessibility service (it lives in the app's own process).

set -euo pipefail
cd "$(dirname "$0")"

PKG=com.homeo.dashvoice
SVC="$PKG/$PKG.ClimateService"
MODEL_SRC=models/vosk-model-small-en-us-0.15
MODEL_DST=/sdcard/Android/data/$PKG/files/model
APK=build/dashvoice.apk

command -v adb >/dev/null || { echo "adb not found"; exit 1; }
[ -f "$APK" ] || { echo "missing $APK - run ./build.sh first"; exit 1; }

echo "Waiting for device..."
adb wait-for-device

echo "[1/4] install"
adb install -r "$APK" | tail -1

echo "[2/4] microphone permission"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

echo "[3/4] speech model"
if adb shell "[ -d $MODEL_DST/am ]" 2>/dev/null; then
  echo "  already present at $MODEL_DST"
else
  [ -d "$MODEL_SRC" ] || {
    echo "  ERROR: $MODEL_SRC not found."
    echo "  Download it with:"
    echo "    curl -LO https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    echo "    unzip vosk-model-small-en-us-0.15.zip -d models/"
    exit 1
  }
  adb shell mkdir -p "$MODEL_DST"
  echo "  pushing ~68 MB..."
  adb push "$MODEL_SRC/." "$MODEL_DST/" | tail -1
fi

echo "[4/4] bind accessibility service"
adb shell settings put secure accessibility_enabled 0
sleep 1
adb shell settings put secure enabled_accessibility_services "$SVC"
sleep 1
adb shell settings put secure accessibility_enabled 1
sleep 3

# dumpsys renders the label inconsistently (sometimes the app label,
# sometimes a truncated class name), so match on the package instead.
if adb shell dumpsys accessibility 2>/dev/null \
     | grep -qiE "com\.homeo\.dashvoice|ClimateService"; then
  echo "  bound OK"
else
  echo "  WARNING: service did not bind. Enable BYD DashVoice manually under"
  echo "  Settings > Accessibility."
fi

echo
echo "Done. Launch it with:"
echo "  adb shell am start -n $PKG/.MainActivity"
echo
echo "To undo the accessibility change later:"
echo "  adb shell settings put secure enabled_accessibility_services ''"
echo "  adb shell settings put secure accessibility_enabled 0"
