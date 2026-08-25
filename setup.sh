#!/usr/bin/env bash
# One-shot setup for DashVoice on a connected BYD head unit.
#
#   1. installs the APK
#   2. grants the microphone permission
#   3. pushes the Vosk English model (only if not already there)
#   4. denies the stock assistant the microphone, so DashVoice can win it
#
# There is no accessibility service any more. DashVoice talks to
# android.hardware.bydauto.* directly, so nothing needs binding and a
# reinstall no longer breaks anything.
#
# Step 4 is the one system change, and it is reversible - see
# docs/head-unit-tweaks.md for the reasoning and the undo command.

set -euo pipefail
cd "$(dirname "$0")"

PKG=com.homeo.dashvoice
XIAODI=com.byd.autovoice.aispeech
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

echo "[4/4] release the microphone from the stock assistant"
# The steering-wheel mic button becomes an unprotected MEDIA_VOICE broadcast
# delivered to every receiver, so Xiaodi wakes too and grabs the single
# available recorder. It cannot be excluded any other way: it is flagged
# PERSISTENT, and it registers its receiver dynamically rather than in its
# manifest, so neither force-stop nor pm disable-user has any effect.
adb shell cmd appops set "$XIAODI" RECORD_AUDIO ignore || true
echo "  $(adb shell cmd appops get "$XIAODI" RECORD_AUDIO 2>/dev/null | tr -d '\r')"

echo
echo "Done. Launch it with:"
echo "  adb shell am start -n $PKG/.MainActivity"
echo
echo "Then tap 'Enable mic-button hook' once, so the steering-wheel button"
echo "works without the app open. It restarts itself at every ignition."
echo
echo "To undo the one system change:"
echo "  adb shell cmd appops set $XIAODI RECORD_AUDIO allow"
