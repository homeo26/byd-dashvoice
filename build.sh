#!/usr/bin/env bash
# Build & sign DashVoice using only Android SDK command-line tools.
#
# Extra work versus a plain project: Vosk ships as an AAR and needs JNA, so we
# unpack both AARs by hand, put their classes.jar on the javac classpath and
# into d8, and place the arm64-v8a .so files under lib/arm64-v8a/ in the APK.
#
# Output: build/dashvoice.apk

set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-$HOME/Library/Android/sdk}"
BT_VER="${BT_VER:-36.0.0}"
PLATFORM="${PLATFORM:-android-36}"

BT="$SDK/build-tools/$BT_VER"
AJ="$SDK/platforms/$PLATFORM/android.jar"

[ -d "$BT" ] || { echo "Build-tools not found at $BT"; exit 1; }
[ -f "$AJ" ] || { echo "android.jar not found at $AJ"; exit 1; }

VOSK_AAR=libs/vosk-android.aar
JNA_AAR=libs/jna.aar
[ -f "$VOSK_AAR" ] || { echo "missing $VOSK_AAR"; exit 1; }
[ -f "$JNA_AAR" ]  || { echo "missing $JNA_AAR"; exit 1; }

rm -rf build/gen build/classes build/dex build/lib
mkdir -p build/{gen,classes,dex,lib/arm64-v8a}

echo "[1/8] unpack AARs (arm64-v8a only)"
rm -rf libs/x && mkdir -p libs/x/vosk libs/x/jna
( cd libs/x/vosk && unzip -o -q ../../vosk-android.aar classes.jar "jni/arm64-v8a/*" )
( cd libs/x/jna  && unzip -o -q ../../jna.aar          classes.jar "jni/arm64-v8a/*" )
cp libs/x/vosk/jni/arm64-v8a/libvosk.so        build/lib/arm64-v8a/
cp libs/x/jna/jni/arm64-v8a/libjnidispatch.so  build/lib/arm64-v8a/
ls -la build/lib/arm64-v8a/ | tail -3

CP="libs/x/vosk/classes.jar:libs/x/jna/classes.jar"

echo "[2/8] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o build/res-compiled.zip

echo "[3/8] aapt2 link"
"$BT/aapt2" link \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --target-sdk-version 28 --min-sdk-version 28 \
  -o build/unsigned-noclasses.apk \
  build/res-compiled.zip

echo "[4/8] javac"
javac -source 1.8 -target 1.8 -Xlint:-options \
  -bootclasspath "$AJ" \
  -classpath "$CP" \
  -d build/classes \
  build/gen/com/homeo/dashvoice/R.java \
  src/com/homeo/dashvoice/BydPermissionContext.java \
  src/com/homeo/dashvoice/BydAcApi.java \
  src/com/homeo/dashvoice/BydBodyworkApi.java \
  src/com/homeo/dashvoice/AppLauncher.java \
  src/com/homeo/dashvoice/Commands.java \
  src/com/homeo/dashvoice/ClimatePreset.java \
  src/com/homeo/dashvoice/CommandReference.java \
  src/com/homeo/dashvoice/VoskEngine.java \
  src/com/homeo/dashvoice/XiaodiBridge.java \
  src/com/homeo/dashvoice/MicKeyService.java \
  src/com/homeo/dashvoice/BootReceiver.java \
  src/com/homeo/dashvoice/KeepAliveJobService.java \
  src/com/homeo/dashvoice/Feedback.java \
  src/com/homeo/dashvoice/MainActivity.java

echo "[5/8] d8 (app + vosk + jna)"
"$BT/d8" --min-api 28 --output build/dex \
  --lib "$AJ" \
  $(find build/classes -name "*.class") \
  libs/x/vosk/classes.jar \
  libs/x/jna/classes.jar
ls -la build/dex/

echo "[6/8] package APK"
cp build/unsigned-noclasses.apk build/unsigned.apk
( cd build/dex && zip -q ../unsigned.apk classes*.dex )
# native libs stored (not deflated) so the loader can mmap them
( cd build && zip -q -0 -r unsigned.apk lib )

if [ ! -f build/debug.keystore ]; then
  echo "  [keystore] generating one-time debug keystore"
  keytool -genkeypair -keystore build/debug.keystore \
    -storepass android -keypass android \
    -alias k -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=dashvoice,O=local,C=US" 2>/dev/null
fi

echo "[7/8] zipalign"
# -p aligns .so files to page boundaries
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk

echo "[8/8] apksigner"
"$BT/apksigner" sign \
  --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias k --min-sdk-version 28 \
  --out build/dashvoice.apk build/aligned.apk

echo
echo "Built: $(pwd)/build/dashvoice.apk ($(wc -c < build/dashvoice.apk | tr -d ' ') bytes)"
echo
echo "Install:"
echo "  adb install -r build/dashvoice.apk"
echo "Push the speech model (once):"
echo "  adb push models/vosk-model-small-en-us-0.15/. \\"
echo "    /sdcard/Android/data/com.homeo.dashvoice/files/model/"
