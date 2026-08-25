#!/usr/bin/env bash
# One-shot setup for DashVoice on a connected BYD head unit.
#
#   1. installs the APK
#   2. grants the microphone permission
#   3. pushes the Vosk English model (only if not already there)
#
# There is no accessibility service, so nothing needs binding and a reinstall
# no longer breaks anything. DashVoice talks to android.hardware.bydauto.*
# directly.
#
# This script makes NO system changes. An earlier version denied the stock
# assistant the microphone so DashVoice would win the steering-wheel button;
# that turned out to hang the assistant's listening overlay, which then held
# input focus and broke fullscreen in other apps until a reboot. See
# docs/head-unit-tweaks.md before considering it.

set -euo pipefail
cd "$(dirname "$0")"

PKG=com.homeo.dashvoice
MODEL_SRC=models/vosk-model-small-en-us-0.15
MODEL_DST=/sdcard/Android/data/$PKG/files/model
APK=build/dashvoice.apk

command -v adb >/dev/null || { echo "adb not found"; exit 1; }
[ -f "$APK" ] || { echo "missing $APK - run ./build.sh first"; exit 1; }

echo "Waiting for device..."
adb wait-for-device

echo "[1/3] install"
adb install -r "$APK" | tail -1

echo "[2/3] microphone permission"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

echo "[3/3] speech model"
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

cat <<'NOTE'

Done. Launch it with:
  adb shell am start -n com.homeo.dashvoice/.MainActivity

The HOLD TO TALK button works immediately.

For the steering-wheel mic button, tap "Enable mic-button hook" once. Note
that the stock assistant receives the same button press and competes for the
microphone - only one recorder is allowed - so the wheel button is not
reliable. Read docs/head-unit-tweaks.md before trying to change that; the
obvious fixes have worse side effects than the problem.
NOTE
