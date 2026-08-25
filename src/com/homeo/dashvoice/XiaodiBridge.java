package com.homeo.dashvoice;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;

/**
 * Bridge to the native BYD assistant ("你好小迪" / Xiaodi).
 *
 * Why this exists: the climate API is inert for non-system apps, but Xiaodi
 * itself runs as system UID and its offline command set covers far more than
 * the climate screen - volume, Bluetooth, brightness, radio, timers, and
 * absolute AC temperature ("空调调到25度"). Rather than reimplement each of
 * those, we can hand Xiaodi the Chinese phrase it already understands.
 *
 * Two mechanisms, both discovered by tracing the steering-wheel mic button:
 *
 *   1. WAKE. Pressing that button produces keycode 304, which the framework's
 *      MediaKeyHandler.sendVoiceButtonIntent() converts into a broadcast of
 *      android.intent.action.MEDIA_VOICE. Xiaodi logs
 *      "当前为方向盘唤醒" (steering-wheel wakeup) on receipt. The framework
 *      itself reports this as a *non-protected* broadcast, so any app can
 *      send it with no permission at all. Confirmed working.
 *
 *      Waking this way starts multi-LocalAsrKernel - the full command
 *      recognizer with slot resources (contacts, radio stations, song names)
 *      - rather than the deliberately strict wake-word model. That means the
 *      audio we play does NOT need to contain "你好小迪", and it is scored by
 *      a much more permissive decoder.
 *
 *   2. SPEAK. Play the Chinese phrase as audio so Xiaodi's microphone hears
 *      it. Crucially this must NOT be done by launching a player activity:
 *      doing so takes window focus and dismisses Xiaodi's listening dialog.
 *      We play in-process with MediaPlayer so focus is untouched.
 *
 * Note the microphone is single-capture on this unit (maxActiveCount: 1), so
 * our own recognizer must have released the mic before Xiaodi can hear
 * anything. Callers must finish listening first.
 */
public class XiaodiBridge {

    private static final String TAG = "DashVoice";

    /** Broadcast the framework sends for the steering-wheel voice button. */
    public static final String ACTION_MEDIA_VOICE = "android.intent.action.MEDIA_VOICE";

    /** Steering-wheel mic keycode, for reference; the broadcast is preferred. */
    public static final int KEYCODE_STEERING_VOICE = 304;

    /**
     * Time between waking Xiaodi and starting playback. It shows a prompt and
     * opens its listening window; speaking too early is lost.
     */
    private static final long WAKE_SETTLE_MS = 1500;

    private final Context ctx;
    private MediaPlayer player;

    public XiaodiBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Wake the assistant. Requires no permission. */
    public void wake() {
        Intent i = new Intent(ACTION_MEDIA_VOICE);
        ctx.sendBroadcast(i);
        Log.i(TAG, "XiaodiBridge: sent " + ACTION_MEDIA_VOICE);
    }

    /**
     * Wake Xiaodi and speak a Chinese command phrase at it.
     *
     * @param phraseWav 16-bit PCM WAV of the Chinese command, WITHOUT the
     *                  wake word (we already woke it explicitly).
     * @return null on success, else a human-readable failure reason.
     */
    public String speakCommand(File phraseWav) {
        if (phraseWav == null || !phraseWav.isFile()) {
            return "phrase audio missing: " + phraseWav;
        }
        wake();
        try {
            Thread.sleep(WAKE_SETTLE_MS);
        } catch (InterruptedException ignored) {
        }
        return play(phraseWav);
    }

    /**
     * Play a file in-process. Uses the assistant usage hint so the audio is
     * routed like speech rather than music, and does not request audio focus
     * (requesting it would duck or dismiss Xiaodi).
     */
    private String play(File f) {
        stop();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            final MediaPlayer self = mp;
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    Log.i(TAG, "XiaodiBridge: playback complete");
                    try { m.release(); } catch (Throwable ignored) {}
                    if (player == self) player = null;
                }
            });
            player = mp;
            mp.start();
            Log.i(TAG, "XiaodiBridge: playing " + f.getName()
                    + " (" + mp.getDuration() + " ms)");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "XiaodiBridge: playback failed", t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public void stop() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Directory holding the Chinese command clips, pushed alongside the
     * speech model:
     *   /sdcard/Android/data/com.homeo.dashvoice/files/zh/
     */
    public static File phraseDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        return new File(base, "zh");
    }

    /** Resolve a clip by logical name, e.g. "volume_up" -> zh/volume_up.wav */
    public static File phrase(Context c, String name) {
        return new File(phraseDir(c), name + ".wav");
    }

    /** Raise the media volume a step without involving Xiaodi at all. */
    public static boolean nudgeVolume(Context c, boolean up) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "nudgeVolume failed", t);
            return false;
        }
    }
}
