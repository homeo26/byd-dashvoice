# BYD DashVoice

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android 9+](https://img.shields.io/badge/Android-9.0+-3DDC84.svg)](https://developer.android.com)
[![Offline](https://img.shields.io/badge/speech-100%25%20offline-blue.svg)](#)
[![No root](https://img.shields.io/badge/root-not%20required-brightgreen.svg)](#)

English voice control for the climate system of a **Chinese-market BYD
DiLink** head unit. Fully offline speech recognition, no root, no
platform signature, no Google or Huawei services.

The stock assistant on these units ("你好小迪") is Mandarin-only and cannot
be switched to English — the firmware ships no English ASR model and no
English voice font. DashVoice replaces it for climate control.

Tested on: **BYD Dilink Di2.1H / 4.0 UI**, Android 9, MediaTek MT6765
(`k65v1_64_bsp`).

## How it works

```
microphone (AudioSource.DEFAULT, 16 kHz mono)
        |
   Vosk small-en-us, constrained to a fixed command grammar
        |
   phrase -> command table
        |
   AccessibilityService -> dispatchGesture() on the BYD climate UI
```

Three non-obvious design decisions, each forced by a measurement on real
hardware. They are the difference between this working and silently
doing nothing.

### 1. The car API cannot be called directly

The unit registers a real system service:

```
89  airconditioning: [android.os.IAirConditioningService]
```

reachable as `context.getSystemService("airconditioning")`, returning an
`android.app.AirConditioningManager` with a full method surface
(`processAcPowerButtonClicked`, `getWindLevel`, `processMainTemperatureChanged`,
and ~40 more).

A third-party app can call it. The calls return cleanly. **Nothing
happens.** Measured: `processAcPowerButtonClicked(true)` returns void, and
`getAcCompressorButtonState()` reads the same value before and after. The
binder ignores writes from non-system UIDs — our app is UID 10067,
`com.byd.airconditioning` is UID 1000 (`android.uid.system`).

Shizuku does not help: it grants shell UID (2000), not system UID (1000).
LSPatch does not help either — the voice assistant is in
`/system/priv-app` with `sharedUserId="android.uid.system"`, so
repackaging it strips the platform signature and with it every
`BYDAUTO_*` permission.

So DashVoice drives the AC app's **user interface** instead. That app runs
as system UID, so when *it* calls the car API the call lands.

### 2. `AudioSource.MIC` returns zero samples on this unit

Measured across all five sources, 4 s each at 16 kHz mono:

| Source | Samples | Peak | Vosk transcription |
|---|---|---|---|
| **DEFAULT** | 64000 | −29.8 dBFS | **"two three four five six seven eight"** |
| VOICE_COMMUNICATION | 64000 | −11.7 dBFS | (nothing) |
| VOICE_RECOGNITION | 64000 | −47.6 dBFS | (nothing) |
| MIC | **0** | — | — |
| CAMCORDER | **0** | — | — |

`MIC` and `CAMCORDER` initialise successfully and then deliver nothing.
`VOICE_RECOGNITION` delivers 81% of its energy below 200 Hz — AC fan
rumble, no intelligible speech.

This matters because **Vosk's own `org.vosk.android.SpeechService`
hardcodes `VOICE_RECOGNITION`** (verifiable in its bytecode: `bipush 6`
before `AudioRecord.<init>`). Using the documented Vosk Android path on
this head unit produces an app that hears nothing. DashVoice therefore
runs its own capture loop and feeds `Recognizer.acceptWaveForm()`
directly.

### 3. A constrained grammar is not an optimisation, it is the feature

Same 4-second recording of "air conditioning on / fan up", decoded two
ways:

```
free-form   : "air conditioning on  fun  up"    <- "fun", confidence 0.51
constrained : "air conditioning on | fan  up"   <- "fan", confidence 1.00
```

Restricting the decoder to the known command phrases both fixes the
misrecognition and splits the utterance into two commands. All five words
land at confidence 1.00.

### Two smaller details

**Gestures, not clicks.** Most BYD AC controls report
`clickable="false"` — they attach touch listeners rather than click
listeners, so `performAction(ACTION_CLICK)` is a no-op. DashVoice resolves
each control by resource id, reads its on-screen bounds, and synthesises a
tap at the centre with `dispatchGesture()`.

**Toggles are read before they are pressed.** Every BYD control is a
toggle, so a naive "AC on" would switch the AC *off* when it was already
on. The nodes expose `selected=true` when active, so `ensure()` reads
current state and no-ops when it already matches.

## Verified control map

Resource ids dumped from a live Di2.1H unit (`com.byd.airconditioning`):

| Control | Resource id |
|---|---|
| AC power | `front_ac_power_id` |
| Compressor | `ac_compressor_id` |
| Auto mode | `control_mode_id` |
| Max cooling | `max_cooling_id` |
| Front / rear defrost | `front_defrost_id` / `rear_defrost_id` |
| Recirculate | `cycle_mode_id` |
| Ventilation | `ventilation_id` |
| Driver temp up / down | `main_arrow_plus_img` / `main_arrow_minus_img` |
| Passenger temp up / down | `deputy_arrow_plus_img` / `deputy_arrow_minus_img` |
| Vent face / foot / defrost | `wind_mode_face_id` / `_foot_id` / `_defrost_id` |
| Fan min / max (endpoints) | `wind_min_id` / `wind_max_id` |
| Fan level track | `wind_level_id` |

`wind_min_id` and `wind_max_id` are **endpoint** buttons — one tap on
`wind_max_id` jumps straight from level 1 to 7. Intermediate levels need a
positional tap on `wind_level_id`. Calibrated on the live UI:

```
x=520 -> 1    x=620 -> 3    x=716 -> 4    x=820 -> 6    x=900 -> 7
```

which is fractions 0.184 .. 0.797 of the track's width. `setFanLevel()`
interpolates within the node's measured bounds rather than hardcoding
pixels, so it survives layout shifts. Verified: requesting 3, 5, 3 yields
exactly `mCurrentWindLevel = 3, 5, 3`.

## Commands

```
air conditioning on/off      ac on/off
compressor on/off            auto mode
max cooling                  recirculate / fresh air
front defrost                rear defrost / defrost off
fan minimum / maximum / full
fan one .. fan seven
temperature up/down          warmer / colder
much warmer / much colder    passenger warmer / colder
vent face / vent feet        ventilation on
```

Speaking two commands in one breath works — Vosk segments them and both
execute in order.

## Install

Requires a computer once, because the accessibility service has to be
enabled and the speech model pushed.

```bash
./build.sh          # produces build/dashvoice.apk (~9 MB)
./setup.sh          # install + push model + bind accessibility service
```

Then open **BYD DashVoice** on the head unit. The checklist at the top must
read three `[ok]` lines before the talk button activates.

Re-run `setup.sh` after every reinstall — replacing the APK kills the
accessibility service, since it runs inside the app's own process.

### Why the model is not bundled

`build.sh` produces a ~9 MB APK and `setup.sh` pushes the 68 MB model
separately to `/sdcard/Android/data/com.homeo.dashvoice/files/model`. That
keeps rebuild-and-reinstall cycles fast during development. For
distribution the model can be moved into `assets/` and unpacked with
`org.vosk.android.StorageService`, giving a single ~60 MB APK.

## Building from source

- JDK 11+ (tested with Corretto 21)
- Android SDK build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`)
- `android.jar` for any API >= 28

No Gradle. `build.sh` unpacks the Vosk and JNA AARs by hand, puts their
`classes.jar` on the javac classpath and into d8, and places the
`arm64-v8a` `.so` files under `lib/arm64-v8a/` in the APK.

```bash
SDK=/path/to/android/sdk BT_VER=36.0.0 PLATFORM=android-36 ./build.sh
```

## Limitations

- **Climate only.** Navigation, media and telephony would be ordinary
  Intents, and Google Assistant on a paired phone already does those in
  English over Bluetooth. The climate system is the part a phone cannot
  reach.
- **The AC screen comes to the foreground** for roughly a second while a
  command executes, because the controls only exist while it is shown.
- **Push-to-talk, no wake word.** Always-on listening costs CPU on an
  MT6765 and would contend with the BYD wake-word engine for the
  single-recorder audio input (`maxActiveCount: 1`).
- **No spoken confirmation.** The unit ships no TTS engine at all
  (`pm query-services -a android.intent.action.TTS_SERVICE` returns
  nothing), so feedback is on-screen. Bundling Piper would fix this.
- **Resource ids are firmware-specific.** These were dumped from Di2.1H /
  4.0 UI. Other BYD generations use different package names and ids, so
  they need re-dumping with `uiautomator dump`.

## Safety

Voice control reduces the time your eyes leave the road compared with
reaching for a touchscreen, but nothing here removes the need to drive
attentively. Use it sensibly.

## License

MIT — see [LICENSE](LICENSE).
