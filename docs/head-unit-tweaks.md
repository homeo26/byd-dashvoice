# Head-unit tweaks

**Summary: there is currently no safe way to guarantee DashVoice wins the
microphone from the steering-wheel button. Both approaches that work have
side effects worse than the problem. The in-app HOLD TO TALK button is
unaffected and always works.**

This file records what was tried, what it broke, and why, so none of it gets
retried.

All of it concerns the stock voice assistant, Xiaodi
(`com.byd.autovoice.aispeech`).

## Why the stock assistant cannot be excluded

The steering-wheel mic button emits `AUTO_MEDIA_VOICE` (scancode 290) from
BYD's `simulate-keys` CAN input device — confirmed from the KeyEvent carried
inside the broadcast:

```
KeyEvent { action=ACTION_UP, keyCode=KEYCODE_AUTO_MEDIA_VOICE,
           scanCode=290, deviceId=6, source=0x101 }
```

The framework converts it to an **unprotected**
`android.intent.action.MEDIA_VOICE` broadcast, delivered in parallel to every
registered receiver. DashVoice and Xiaodi both receive it, and neither can
exclude the other.

Xiaodi cannot be removed from the path:

| Approach | Result |
|---|---|
| `pm disable-user` | No effect — its receiver is registered **dynamically**, not in the manifest, so disabling manifest components does nothing |
| `am force-stop` / `am kill` | No effect — flagged `PERSISTENT`, Android respawns it immediately |
| Edit `/system/usr/keylayout/simulate-keys.kl` | Impossible — `/` is mounted read-only, `uid=2000(shell)`, no `su`, `ro.secure=1`, `build.type=user` |
| `settings put system voice_wakeup_mode 0` | No effect — governs the spoken wake word, not the button |
| Disable `DrawOverlayDetailsActivity` | Refused — `SecurityException: Shell cannot change component state` for a privileged system app |

Since the mic input device allows a single recorder
(`maxActiveCount: 1`), whichever app calls `startRecording()` first wins, and
Xiaodi is already running.

## Autostart is blocked by the firmware

DashVoice cannot start itself after a reboot. **You must open the app once
per boot**, after which `MicKeyService` runs as a foreground service and stays
up.

This is not fixable from the app side. The firmware patches
`ActivityManager` with a vendor self-start filter that refuses to start a
third-party process that is not already running. Every launch mechanism goes
through the same check, so all of them fail:

```
ActivityManager: ssc_skip bindServiceLocked 1000 process android want to bind 10073
ActivityManager: UID 10073 is not running
ActivityManager: packageName 10073  NOT RUNNING
ActivityManager: ssc_skip bindServiceLocked 1000 want to bind 10073 package com.homeo.dashvoice ignored !!!
JobScheduler: Error executing JobStatus{... KeepAliveJobService ... PERSISTED READY}
```

Tried and blocked:

| Mechanism | Result |
|---|---|
| `BOOT_COMPLETED` receiver | `BroadcastQueue: ssc_skip reciever ... ignored !!!` |
| `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `USER_PRESENT` | same filter |
| `MY_PACKAGE_REPLACED` | same filter |
| Persisted `JobScheduler` job | survives reboot and reaches `READY`, then `Error executing JobStatus` because the process cannot be started |

System packages are unaffected — the stock assistant's `PowerOnReceiver`
receives `BOOT_COMPLETED` normally. There is no user-facing whitelist: no
self-start settings screen, no `ssc` service, and no config file under
`/system/etc`.

Note that `am force-stop` additionally sets Android's stopped flag, which
suppresses broadcasts to the app until it is launched by hand. That is a
separate mechanism from the vendor filter and worth remembering when
debugging.

### What is kept anyway

`BootReceiver` and `KeepAliveJobService` are both retained. They cost nothing
and they do work once the process is alive — the job re-checks every five
minutes and restarts `MicKeyService` if it was killed for memory, which is
useful mid-drive. They simply cannot perform the initial cold start.

## Autostart is blocked by the firmware

DashVoice cannot start itself after a reboot. **You must open the app once
per boot**, after which `MicKeyService` runs as a foreground service and stays
up.

This is not fixable from the app side. The firmware patches
`ActivityManager` with a vendor self-start filter that refuses to start a
third-party process that is not already running. Every launch mechanism goes
through the same check, so all of them fail:

```
ActivityManager: ssc_skip bindServiceLocked 1000 process android want to bind 10073
ActivityManager: UID 10073 is not running
ActivityManager: packageName 10073  NOT RUNNING
ActivityManager: ssc_skip bindServiceLocked 1000 want to bind 10073 package com.homeo.dashvoice ignored !!!
JobScheduler: Error executing JobStatus{... KeepAliveJobService ... PERSISTED READY}
```

Tried and blocked:

| Mechanism | Result |
|---|---|
| `BOOT_COMPLETED` receiver | `BroadcastQueue: ssc_skip reciever ... ignored !!!` |
| `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `USER_PRESENT` | same filter |
| `MY_PACKAGE_REPLACED` | same filter |
| Persisted `JobScheduler` job | survives reboot and reaches `READY`, then `Error executing JobStatus` because the process cannot be started |

System packages are unaffected — the stock assistant's `PowerOnReceiver`
receives `BOOT_COMPLETED` normally. There is no user-facing whitelist: no
self-start settings screen, no `ssc` service, and no config file under
`/system/etc`.

Note that `am force-stop` additionally sets Android's stopped flag, which
suppresses broadcasts to the app until it is launched by hand. That is a
separate mechanism from the vendor filter and worth remembering when
debugging.

### What is kept anyway

`BootReceiver` and `KeepAliveJobService` are both retained. They cost nothing
and they do work once the process is alive — the job re-checks every five
minutes and restarts `MicKeyService` if it was killed for memory, which is
useful mid-drive. They simply cannot perform the initial cold start.

## Do not do this: deny RECORD_AUDIO

```sh
# DO NOT USE
adb shell cmd appops set com.byd.autovoice.aispeech RECORD_AUDIO ignore
```

This **does** work for its stated purpose. DashVoice then wins the microphone
every time, verified by a fresh `rejectTime` on every button press.

**But it breaks fullscreen in every other app.** Mode `ignore` makes the
recorder return *silence* rather than an *error*, so Xiaodi opens its
listening overlay, waits forever for audio that never arrives, and never
tears the window down. That leaves a focusable zero-pixel overlay holding
input focus:

```
mCurrentFocus    = Window{... com.byd.autovoice.aispeech}
                   ty=APPLICATION_OVERLAY, Requested w=0 h=0
                   mSystemUiVisibility=0x0
mFocusedApp      = com.andrerinas.headunitrevived/...MainActivity
mTopIsFullscreen = false
```

Android derives status- and navigation-bar visibility from the **focused**
window. That stuck overlay declares no immersive flags, so it overrides the
foreground app's fullscreen request and the bars reappear. Observed with
OpenHeadUnit; it will affect any immersive app.

Worse, it cannot be cleared at runtime — `force-stop` and `am kill` do not
work on a `PERSISTENT` app, and restoring the appop does not make Xiaodi
tear down the window it is already holding. **It takes a reboot.**

Undo:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech RECORD_AUDIO allow
adb reboot          # required; the stuck window survives the appop change
```

### Untested idea

Mode `errored` (rather than `ignore`) makes the call throw a
`SecurityException` instead of returning silence, which Xiaodi may catch and
handle by closing its UI cleanly:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech RECORD_AUDIO deny
```

If it dismisses cleanly this gives a reliable microphone with no stuck
window. **Untested — try it parked, and be ready to reboot.**

## Do not do this either: deny the overlay permission

```sh
# DO NOT USE
adb shell cmd appops set com.byd.autovoice.aispeech SYSTEM_ALERT_WINDOW ignore
```

Xiaodi's listening popup is three overlay windows, all gated by this appop:

| Window | Type                  | Size     |
|--------|-----------------------|----------|
| #0     | `SYSTEM_ERROR`        | 704x120  |
| #3     | `APPLICATION_OVERLAY` | 336x112  |
| #4     | `APPLICATION_OVERLAY` | 1280x660 |

Denying it does hide them — WindowManager reports `mAppOpVisibility=false`
on all three. But Xiaodi checks whether it can draw overlays and, finding
that it cannot, launches the system overlay-permission screen **on every mic
button press**:

```
mCurrentFocus=com.byd.systemsettings/.permission.view.DrawOverlayDetailsActivity
```

A full-screen permission nag per press is worse than the popup it removes,
and it also blocks launching Xiaodi deliberately from the home screen.

Undo:

```sh
adb shell cmd appops set com.byd.autovoice.aispeech SYSTEM_ALERT_WINDOW default
```

One of its windows is not an overlay at all, so no appop reaches it:

```
com.byd.autovoice.aispeech/com.byd.autovoice_view.floatwindow.FloatActivity
ty=BASE_APPLICATION
```

## Original values, for restoring a clean state

```sh
P=com.byd.autovoice.aispeech
adb shell cmd appops set $P RECORD_AUDIO allow
adb shell cmd appops set $P SYSTEM_ALERT_WINDOW default
adb shell cmd appops set $P PLAY_AUDIO default
adb shell pm default-state $P              # enabled=0 (DEFAULT)
adb shell settings put system voice_wakeup_mode 1
adb reboot
```

## What Xiaodi does on every press, unavoidably

It wakes, plays its own prompt, shows its popup, and calls
`setNaviMuteState(true)`, releasing it about 230 ms later. No audio stream is
left muted.

Because that duck lands at t=0 on the navigation channel — which is where
DashVoice's own cues play — the mic-open cue is delayed 400 ms to land clear
of it. See `Feedback.listening()`.

## Still unsolved

Suppressing the popup. The remaining untried candidate is an
`AccessibilityService` declaring `canRequestFilterKeyEvents` and consuming
the key in `onKeyEvent()`. The accessibility input filter runs before the
window-manager policy, so consuming it there should stop the broadcast ever
being emitted.

Unproven on this firmware, and there is no prior art: the third-party
`ru.bydconnect` app declares `canRequestFilterKeyEvents="true"` but never
implements `onKeyEvent` (0 occurrences in its dex), and its accessibility
service only writes log lines.
