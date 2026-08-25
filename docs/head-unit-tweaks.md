# Head-unit tweaks

DashVoice needs one `appops` change on the head unit to work. It does not
uninstall, disable, or modify any BYD app, and it is reversible with a single
command. `appops` state is persisted in `/data/system/appops.xml`, so
these survive a reboot.

It targets the stock voice assistant, Xiaodi (`com.byd.autovoice.aispeech`).

## Why they are needed

The steering-wheel mic button is scancode 582 in
`/system/usr/keylayout/ACCDET.kl`, mapped to `VOICE_ASSIST`
(`KEYCODE_VOICE_ASSIST`, 231). BYD's framework turns that key into an
**unprotected** `android.intent.action.MEDIA_VOICE` broadcast.

That broadcast is delivered in parallel to every registered receiver, so
DashVoice and Xiaodi both get it. Xiaodi cannot be excluded, because:

- it is flagged `PERSISTENT`, so `am force-stop` and `am kill` do not keep it
  down — Android restarts it immediately;
- it registers its `MEDIA_VOICE` receiver **dynamically at runtime**, so
  `pm disable-user` does not stop it (disabling only affects components
  declared in the manifest).

Remapping the key at the input layer is not possible on this firmware: `/` is
mounted read-only, adb runs as `uid=2000(shell)`, there is no `su`, and the
build is `ro.secure=1` / `build.type=user`.

So instead of removing Xiaodi, we take away its hold on the microphone.

## 1. Stop it taking the microphone

The unit's input device allows one active recorder. Whichever app calls
`startRecording()` first wins, and Xiaodi is already running.

```sh
adb shell cmd appops set com.byd.autovoice.aispeech RECORD_AUDIO ignore
```

Verify — a fresh `rejectTime` after pressing the button means Xiaodi was
refused the mic:

```sh
adb shell cmd appops get com.byd.autovoice.aispeech RECORD_AUDIO
# RECORD_AUDIO: ignore; time=...; rejectTime=...
```

Undo:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech RECORD_AUDIO allow
```

## 2. Stop its popup appearing — DOES NOT WORK, do not use

Xiaodi's listening UI is built from three overlay windows, all gated by the
`SYSTEM_ALERT_WINDOW` appop:

| Window | Type                  | Size     |
|--------|-----------------------|----------|
| #0     | `SYSTEM_ERROR`        | 704x120  |
| #3     | `APPLICATION_OVERLAY` | 336x112  |
| #4     | `APPLICATION_OVERLAY` | 1280x660 |

Denying the op does hide them — WindowManager reports
`mAppOpVisibility=false` on all three, which is verifiable:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech SYSTEM_ALERT_WINDOW ignore
adb shell dumpsys window windows | grep -A14 "Window{.*aispeech" \
  | grep -E "Window\{|ty=|mAppOpVisibility"
```

**But this makes things worse, so do not do it.** Xiaodi checks whether it can
draw overlays and, finding that it cannot, launches the system overlay
permission screen on every mic-button press:

```
mCurrentFocus=com.byd.systemsettings/.permission.view.DrawOverlayDetailsActivity
```

A full-screen permission nag on every press is worse than the small popup it
was meant to remove. Revert it:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech SYSTEM_ALERT_WINDOW default
```

The popup therefore cannot be suppressed through appops. The only remaining
option is to stop the key reaching Xiaodi at all — see "Suppressing the popup"
below.

### Why the popup cannot be suppressed any other way

One Xiaodi window is not even an overlay, so no appop applies to it:

```
com.byd.autovoice.aispeech/com.byd.autovoice_view.floatwindow.FloatActivity
ty=BASE_APPLICATION
```

## Suppressing the popup

Not solved. Every non-invasive avenue is closed:

| Approach | Result |
|---|---|
| `pm disable-user` | No effect — receiver is registered dynamically |
| `am force-stop` / `am kill` | No effect — app is flagged `PERSISTENT` |
| `appops SYSTEM_ALERT_WINDOW ignore` | Backfires into a permission nag screen |
| `appops PLAY_AUDIO ignore` | Does not silence its prompt |
| Edit `ACCDET.kl` | `/` is read-only, no root |

The remaining candidate is an `AccessibilityService` declaring
`canRequestFilterKeyEvents` and consuming `KEYCODE_VOICE_ASSIST` (231) in
`onKeyEvent()`. The accessibility input filter runs before the window manager
policy, so consuming the key there should prevent BYD's handler from ever
emitting the `MEDIA_VOICE` broadcast — meaning Xiaodi never wakes.

This is unproven on this firmware. The third-party `ru.bydconnect` app
declares `canRequestFilterKeyEvents="true"` but never implements `onKeyEvent`
(0 occurrences in its dex), so it is not evidence that the technique works
here.

## Side effects

Xiaodi stays installed and enabled. It still receives the broadcast, still
runs, and still briefly ducks navigation audio via
`setNaviMuteState(true)`, releasing it about 230 ms later — no audio stream is
left muted. Its own spoken prompts and nav TTS (`NaviTTSService`) are
untouched.

Its popup still appears; that is unsolved, see below. Because the duck lands
at t=0, DashVoice delays its "listening" blip by
400 ms so the cue is not swallowed. See `Feedback.listening()`.

## What was tried and reverted

- `pm disable-user com.byd.autovoice.aispeech` — ineffective, because the
  receiver is registered dynamically rather than declared in the manifest.
  Reverted with `pm enable`.
- `cmd appops set ... PLAY_AUDIO ignore` — did not silence its prompt.
  Reverted to `allow`.
- Editing `/system/usr/keylayout/ACCDET.kl` — impossible, `/` is read-only
  and there is no root.
