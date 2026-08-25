# BYD DashVoice

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android 9+](https://img.shields.io/badge/Android-9.0+-3DDC84.svg)](https://developer.android.com)
[![Offline](https://img.shields.io/badge/speech-100%25%20offline-blue.svg)](#)
[![No root](https://img.shields.io/badge/root-not%20required-brightgreen.svg)](#)

English voice control for the climate system of a **Chinese-market BYD
DiLink** head unit. Fully offline speech recognition, no root, no platform
signature, no Google or Huawei services.

The stock assistant on these units ("你好小迪") is Mandarin-only and cannot
be switched to English — the firmware ships no English ASR model and no
English voice font. DashVoice replaces it for climate control, and it is
triggered by the same steering-wheel microphone button.

Tested on: **BYD Dilink Di2.1H / 4.0 UI**, Android 9, MediaTek MT6765
(`k65v1_64_bsp`).

## How it works

```
steering-wheel mic button
        |  (AUTO_MEDIA_VOICE -> unprotected MEDIA_VOICE broadcast)
   MicKeyService (foreground, survives reboot)
        |
   microphone (AudioSource.DEFAULT, 16 kHz mono)
        |
   Vosk small-en-us, constrained to a fixed command grammar
        |
   phrase -> command table
        |
   android.hardware.bydauto.ac.BYDAutoAcDevice  (direct API, by reflection)
        |
   spoken confirmation: "Done" / "Didn't catch that" / "The car refused that"
```

No UI automation, no accessibility service, no synthesised taps. Commands
go to the vehicle API directly.

Each of the decisions below was forced by a measurement on real hardware.
They are the difference between this working and silently doing nothing.

### 1. There are two AC APIs, and the obvious one is a decoy

The unit registers a real system service:

```
89  airconditioning: [android.os.IAirConditioningService]
```

reachable as `getSystemService("airconditioning")`, returning an
`android.app.AirConditioningManager` with a full method surface
(`processAcPowerButtonClicked`, `getWindLevel`, and ~40 more).

A third-party app can call it, the calls return cleanly, and **nothing
happens.** It is an inert UI wrapper: setters no-op and getters return
frozen defaults. Measured — it reported 17 °C while the cabin was actually
set to 19 °C.

The API that works is a different class family:

```java
Class<?> dev = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice");
Object ac = dev.getMethod("getInstance", Context.class).invoke(null, ctx);
```

backed by `libbydauto.so` and the `android.gui.BYDAutoServer` native
service. These calls reach the MCU.

### 2. The permission model has two layers, and only one is grantable

- `BYDAUTO_*_COMMON` are `dangerous`, so runtime-grantable. They gate
  `getInstance()`.
- `BYDAUTO_*_GET` and `BYDAUTO_*_SET` are `signature|privileged` and are
  enforced **individually inside every setter**. A normal app cannot hold
  them.

Which is why `BydPermissionContext` exists: a `ContextWrapper` that returns
`PERMISSION_GRANTED` for `BYDAUTO_*` checks. The device object is obtained
through it, so the per-method checks inside the framework class pass. The
call still lands on the real MCU binder — this is not a simulation layer.

Shizuku does not help here: it grants shell UID (2000), not system UID
(1000). LSPatch does not either — the AC app is in `/system/priv-app` with
`sharedUserId="android.uid.system"`, so repackaging strips the platform
signature and with it every `BYDAUTO_*` permission.

### 3. `AudioSource.MIC` returns zero samples on this unit

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
this head unit produces an app that hears nothing. DashVoice runs its own
capture loop and feeds `Recognizer.acceptWaveForm()` directly.

### 4. A constrained grammar is not an optimisation, it is the feature

Same 4-second recording of "air conditioning on / fan up", decoded two
ways:

```
free-form   : "air conditioning on  fun  up"    <- "fun", confidence 0.51
constrained : "air conditioning on | fan  up"   <- "fan", confidence 1.00
```

Restricting the decoder to known command phrases both fixes the
misrecognition and splits the utterance into two commands.

### 5. The speech gate is measured, not guessed

An early build declared speech at a fixed mean amplitude of 550. Real
speech in this cabin peaks between **355 and 563**, so the gate often sat
above the talker; capture then hit its no-speech timeout and returned
nothing on every press.

The gate is now derived per capture: the first four 100 ms blocks measure
the ambient floor, and speech is declared at 2.5x that floor. The floor
reads about 100 with the fan running, putting the gate near 250. Cabin
noise varies far too much with fan speed for any constant to work.

Recognition also commits early. Once a partial result already spells a
complete command and holds steady for 300 ms, it is acted on rather than
waiting for the recogniser's own end-of-utterance decision, which for
two-word commands often never arrives.

## Commands

Grouped as they appear on screen. Each group also accepts synonyms — the
grammar holds 128 entries, most of them pronunciation variants, because the
small English model mishears digits ("fan two" also accepts "fan to").

| Group | Examples |
|---|---|
| Power | `ac on`, `ac off`, `air conditioning on` |
| How you feel | `cool it down`, `warm it up`, `comfort mode`, `i'm hot` |
| Temperature | `temperature twenty two`, `warmer`, `cooler`, `passenger warmer` |
| Fan | `fan three`, `fan max`, `fan low`, `fan up`, `fan down` |
| Vent direction | `vent face`, `vent feet`, `vent everywhere` |
| Demisting | `defrost windshield`, `max defrost`, `rear defrost` |
| Air source | `recirculate`, `fresh air` |
| Modes | `auto mode`, `manual mode`, `max cooling`, `fan only` |
| Compressor | `compressor on`, `compressor off` |

Speaking two commands in one breath works — they are segmented and both
execute in order.

Every phrase on the app's screen is tappable and runs the identical
dispatch path, which makes it easy to tell a recognition problem from a
vehicle problem.

## Triggering

The in-app **HOLD TO TALK** button always works.

The steering-wheel mic button emits `AUTO_MEDIA_VOICE` (scancode 290, from
BYD's `simulate-keys` CAN input device), which the framework turns into an
**unprotected** `android.intent.action.MEDIA_VOICE` broadcast.
`MicKeyService` listens for it, so voice control works without opening the
app, and a `BootReceiver` brings it back at every ignition.

**But it is not reliable, and there is currently no safe fix.** That
broadcast is delivered in parallel to every receiver, so the stock assistant
wakes too and competes for the microphone — the input device allows exactly
one recorder, and it is already running.

It cannot be removed from the path. It is flagged `PERSISTENT`, so
`am force-stop` and `am kill` do not hold; it registers its receiver
**dynamically** rather than in its manifest, so `pm disable-user` does not
apply; and remapping the key is impossible because `/` is mounted read-only
with no root.

Denying it the microphone via `appops` does work — and then hangs its
listening overlay, which holds input focus with no immersive flags and
**breaks fullscreen in other apps** until a reboot. Denying its overlay
permission hides the popup and then launches the system permission screen on
every press. Both are documented as do-not-use in
[docs/head-unit-tweaks.md](docs/head-unit-tweaks.md), along with the one
untried candidate: an `AccessibilityService` consuming the key in
`onKeyEvent()` before the broadcast is ever emitted.

**Known annoyance:** even when DashVoice does win, the stock assistant still
flashes its popup and plays its own prompt, costing roughly a second before
you speak.

## Install

```bash
./build.sh          # produces build/dashvoice.apk (~9.5 MB)
./setup.sh          # install + model. Makes no system changes.
```

Then open **BYD DashVoice** on the head unit. **HOLD TO TALK** works
immediately. Tap **Enable mic-button hook** once if you also want the
steering-wheel button, bearing in mind the reliability caveat above.

Unlike earlier versions, `setup.sh` does not need re-running after a
reinstall — there is no accessibility service to rebind. The mic hook does
need re-enabling if the app's data is cleared.

### Why the model is not bundled

`build.sh` produces a ~9.5 MB APK and `setup.sh` pushes the 68 MB model
separately to `/sdcard/Android/data/com.homeo.dashvoice/files/model`, which
keeps rebuild cycles fast. For distribution the model can be moved into
`assets/` and unpacked with `org.vosk.android.StorageService`, giving a
single ~60 MB APK.

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

- **Climate only, by design.** Navigation, media and telephony are ordinary
  Intents, and a paired phone already handles those in English. The climate
  system is the part a phone cannot reach.
- **Windows are refused by the car.** The commands exist and the API accepts
  them — every setter returns 0 and the framework logs the call — but
  nothing moves. `getWindowPermitState` reads 0, a body-control interlock
  that needs the signature permission to lift. Only the driver window is
  even addressable; the other positions report `65535`. The app says "The
  car refused that" rather than failing silently.
- **No sunroof on this car.** Those getters return `65535`. The commands are
  disabled in the UI.
- **The steering-wheel button is unreliable.** The stock assistant receives
  the same press and competes for the single available recorder. Every way to
  stop it has a worse side effect than the problem — see
  [docs/head-unit-tweaks.md](docs/head-unit-tweaks.md). HOLD TO TALK is
  unaffected.
- **Push-to-talk, no wake word.** Always-on listening costs CPU on an MT6765
  and would hold the single-recorder audio input permanently.
- **Firmware-specific.** Verified on Di2.1H / 4.0 UI. Other BYD generations
  may expose a different `android.hardware.bydauto.*` surface.

## Safety

Voice control reduces the time your eyes leave the road compared with
reaching for a touchscreen, but nothing here removes the need to drive
attentively. Use it sensibly.

## License

MIT — see [LICENSE](LICENSE). Bundled asset credits are in
[ATTRIBUTION.md](ATTRIBUTION.md).
