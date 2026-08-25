# Head-unit tweaks

DashVoice needs two `appops` changes on the head unit to work well. Neither
uninstalls, disables, or modifies any BYD app, and both are reversible with a
single command. `appops` state is persisted in `/data/system/appops.xml`, so
these survive a reboot.

Both target the stock voice assistant, Xiaodi (`com.byd.autovoice.aispeech`).

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

So instead of removing Xiaodi, we take away the two things that make it
interfere.

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

## 2. Stop its popup appearing

Xiaodi's listening UI is built from three overlay windows, all gated by the
`SYSTEM_ALERT_WINDOW` appop:

| Window | Type                  | Size     |
|--------|-----------------------|----------|
| #0     | `SYSTEM_ERROR`        | 704x120  |
| #3     | `APPLICATION_OVERLAY` | 336x112  |
| #4     | `APPLICATION_OVERLAY` | 1280x660 |

```sh
adb shell cmd appops set com.byd.autovoice.aispeech SYSTEM_ALERT_WINDOW ignore
```

Verify — WindowManager reports `mAppOpVisibility=false` on each:

```sh
adb shell dumpsys window windows | grep -A14 "Window{.*aispeech" \
  | grep -E "Window\{|ty=|mAppOpVisibility"
```

Undo:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech SYSTEM_ALERT_WINDOW default
```

### Known limitation

One Xiaodi window is **not** an overlay and cannot be suppressed this way:

```
com.byd.autovoice.aispeech/com.byd.autovoice_view.floatwindow.FloatActivity
ty=BASE_APPLICATION
```

It is a normal Activity, so the `SYSTEM_ALERT_WINDOW` appop does not apply. If
a popup still appears after tweak 2, this is the one.

## Side effects

Xiaodi stays installed and enabled. It still receives the broadcast, still
runs, and still briefly ducks navigation audio via
`setNaviMuteState(true)`, releasing it about 230 ms later — no audio stream is
left muted. Its own spoken prompts and nav TTS (`NaviTTSService`) are
untouched.

Because that duck lands at t=0, DashVoice delays its "listening" blip by
400 ms so the cue is not swallowed. See `Feedback.listening()`.

## What was tried and reverted

- `pm disable-user com.byd.autovoice.aispeech` — ineffective, because the
  receiver is registered dynamically rather than declared in the manifest.
  Reverted with `pm enable`.
- `cmd appops set ... PLAY_AUDIO ignore` — did not silence its prompt.
  Reverted to `allow`.
- Editing `/system/usr/keylayout/ACCDET.kl` — impossible, `/` is read-only
  and there is no root.
