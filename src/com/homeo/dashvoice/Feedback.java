package com.homeo.dashvoice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.SparseIntArray;

/**
 * Spoken feedback for DashVoice. Four short cues, no user configuration.
 *
 * <ul>
 *   <li>{@link #listening()} — "Listening", when the mic opens.</li>
 *   <li>{@link #ack()} — "Done", command accepted by the car.</li>
 *   <li>{@link #unheard()} — "Didn't catch that", nothing recognised or no
 *       command matched.</li>
 *   <li>{@link #refused()} — "The car refused that", a real command that the
 *       vehicle rejected, such as a window while the body interlock is on.</li>
 * </ul>
 *
 * <p>Speech rather than abstract tones, because the tones were hard to tell
 * apart from Xiaodi's own prompt. Splitting failure into "didn't catch" and
 * "refused" matters here: those two have completely different causes, and
 * conflating them into one buzz hid which had happened.
 *
 * <p><b>Routing.</b> Navigation guidance, so the car ducks music underneath
 * instead of the cue competing at full media volume. Note that Xiaodi calls
 * {@code setNaviMuteState(true)} when it wakes and releases it about 230 ms
 * later, which is why {@link #listening()} is delayed past that window.
 *
 * <p><b>Threading.</b> All audio work runs on a dedicated
 * {@link HandlerThread}. An early {@code ToneGenerator} version built an
 * {@code AudioTrack} on the main thread per play, which made the UI lag.
 *
 * <p>Clips are generated speech (Piper, "amy"), trimmed and loudness
 * normalised to -18 LUFS. See ATTRIBUTION.md.
 */
public class Feedback {

    private static final String TAG = "DashVoice";

    /**
     * Playback level, 0..1. Deliberately below unity: the cue only has to be
     * intelligible over the fan, and Xiaodi's prompt often overlaps it.
     */
    private static final float VOLUME = 0.7f;

    /** Delay before the mic-open cue, clearing Xiaodi's ~230 ms nav mute. */
    private static final long LISTEN_DELAY_MS = 400;

    private static SoundPool pool;
    private static Handler bg;
    private static final SparseIntArray loaded = new SparseIntArray();

    /** Initialise once per process. Safe to call repeatedly. */
    public static synchronized void init(Context ctx) {
        if (pool != null) return;

        HandlerThread t = new HandlerThread("dashvoice-sfx",
                android.os.Process.THREAD_PRIORITY_AUDIO);
        t.start();
        bg = new Handler(t.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    // Nav guidance: the car ducks media under it, which is the
                    // behaviour wanted for a short spoken cue. Plain
                    // USAGE_MEDIA played at music volume and felt intrusive;
                    // USAGE_ASSISTANCE_SONIFICATION routed to STREAM_SYSTEM,
                    // which this unit holds at 8 of 39 and was inaudible.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(2)
                    .setAudioAttributes(attrs).build();
        } else {
            pool = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
        }

        final Context app = ctx.getApplicationContext();
        final int[] res = {
            R.raw.sfx_listen, R.raw.sfx_ok, R.raw.sfx_unheard, R.raw.sfx_refused
        };
        bg.post(new Runnable() {
            @Override public void run() {
                for (int r : res) {
                    try {
                        int id = pool.load(app, r, 1);
                        synchronized (loaded) { loaded.put(r, id); }
                    } catch (Throwable e) {
                        Log.w(TAG, "Feedback: load failed for res " + r, e);
                    }
                }
                Log.i(TAG, "Feedback: " + res.length + " voice cues ready");
            }
        });
    }

    public static void listening()    { playDelayed(R.raw.sfx_listen, LISTEN_DELAY_MS); }
    /** Same cue with no delay, for the in-app sound check. */
    public static void listeningNow() { play(R.raw.sfx_listen); }
    public static void ack()          { play(R.raw.sfx_ok); }
    public static void unheard()      { play(R.raw.sfx_unheard); }
    public static void refused()      { play(R.raw.sfx_refused); }

    /**
     * A command was matched, so a failure here means the car rejected it
     * rather than the recogniser mishearing.
     */
    public static void forResult(Commands.Result r) {
        if (r == null || !r.success) refused(); else ack();
    }

    private static void playDelayed(final int resId, long delayMs) {
        final Handler h = bg;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override public void run() { play(resId); }
        }, delayMs);
    }

    private static void play(final int resId) {
        final SoundPool p = pool;
        final Handler h = bg;
        if (p == null || h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                int soundId;
                synchronized (loaded) { soundId = loaded.get(resId, -1); }
                if (soundId <= 0) return;   // still loading; drop rather than block
                try { p.play(soundId, VOLUME, VOLUME, 1, 0, 1f); }
                catch (Throwable e) { Log.w(TAG, "Feedback: play failed", e); }
            }
        });
    }

    private Feedback() {}
}
