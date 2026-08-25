# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-08-25

### Changed
- **Climate backend rewritten on the direct BYDAuto device API.** No more
  `AccessibilityService`, no more UI tapping. Commands land via
  `android.hardware.bydauto.ac.BYDAutoAcDevice` reached by reflection,
  with a `ContextWrapper` that no-ops `BYDAUTO_*` permission checks. Works
  from a normal third-party app on the tested Di2.1H unit — no root, no
  platform signature.
- Two-phase temperature setter: `setAcTemperature(zone, tempC, source=0, phase)`
  called with `phase=1` then `phase=2` about 500 ms apart. The commit
  return code is unreliable, so success is verified by re-reading
  `getTemprature(zone)`.
- MCU write throttling: setters get ~350 ms spacing between commands, and
  `setTemp` internally spaces its two phases by 500 ms.

### Removed
- `ClimateService` (AccessibilityService) and its config XML.
- Accessibility service metadata from the manifest.
- All references to synthetic gesture dispatch.

### Retained
- `VoskEngine` and `Commands` grammar (dispatch now hits the API).
- `XiaodiBridge` — still useful to wake the native assistant with the
  MEDIA_VOICE broadcast (no permission required) even without speaking.

### Verified end-to-end
- `ac on` → `start(0)` → AC on
- `fan three` → `setAcWindLevel(3, 0)` → level 3
- `temperature twenty two` → two-phase commit → `driver temp -> 22°C`
- `ac off` → `stop(0)` → AC off

## [0.1.0] - 2026-08-25

### Added
- Initial release: offline English voice control for the BYD DiLink climate
  system.
- Offline recognition with Vosk `small-en-us-0.15` and a constrained
  command grammar of ~30 phrases.
- Custom audio capture loop on `MediaRecorder.AudioSource.DEFAULT`,
  bypassing `org.vosk.android.SpeechService` which hardcodes
  `VOICE_RECOGNITION` (a source that delivers no intelligible speech on
  this head unit).
- `ClimateService`, an `AccessibilityService` that resolves BYD AC controls
  by resource id and drives them with `dispatchGesture()`. Gesture dispatch
  is required because most BYD controls report `clickable="false"`.
- State-aware toggling: `ensure()` reads a control's `selected` state and
  no-ops when it already matches, so "AC on" cannot switch the AC off.
- Absolute fan level control (`fan one` .. `fan seven`) via positional tap
  on the `wind_level_id` track, interpolated from the node's live bounds.
- Multi-command utterances: "air conditioning on, fan five" executes both.
- Manual test buttons that run phrases through the same match-and-dispatch
  path as speech, so the control layer can be validated without talking.
- Setup checklist on the main screen covering microphone permission,
  accessibility service binding, and speech model presence.
- `setup.sh` handling install, model push, and the accessibility binding
  sequence that this firmware requires (`accessibility_enabled` must go
  0 -> write service list -> 1; writing the list while already enabled does
  not bind).
- `build.sh` with no Gradle: unpacks the Vosk and JNA AARs, wires their
  `classes.jar` into javac and d8, and packages `arm64-v8a` native
  libraries.

### Notes
- Verified on BYD Dilink Di2.1H / 4.0 UI, Android 9, MediaTek MT6765
  (`k65v1_64_bsp`).
- Fan level control verified end to end: requesting levels 3, 5, 3 produced
  `mCurrentWindLevel = 3, 5, 3` in the AC app's own logs.
- The `airconditioning` system service is reachable from a third-party app
  but silently ignores writes from non-system UIDs, which is why this
  project drives the UI rather than the API.
