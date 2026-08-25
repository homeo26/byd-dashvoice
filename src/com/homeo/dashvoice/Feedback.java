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
 * Audio feedback for DashVoice: three fixed clips, no user configuration.
 *
 * <ul>
 *   <li>{@link #listening()} — short blip when the mic opens, so you know
 *       DashVoice (not Xiaodi) took the button.</li>
 *   <li>{@link #ack()} — command understood and accepted by the car.</li>
 *   <li>{@link #nack()} — nothing recognised, no command matched, or the
 *       car refused the write.</li>
 * </ul>
 *
 * <p><b>Threading:</b> all audio work happens on a dedicated
 * {@link HandlerThread}. An earlier {@code ToneGenerator} version constructed
 * an {@code AudioTrack} on the main thread on every play and on every volume
 * change, which is what made the UI lag on button presses.
 *
 * <p>Clips are from the "GUI sounds collection" by Paolo D'Emilio (copyc4t)
 * on OpenGameArt, CC-BY 3.0. See ATTRIBUTION.md.
 */
public class Feedback {

    private static final String TAG = "DashVoice";

    /**
     * Full scale. The stream itself sets the loudness, and on this unit the
     * media stream sits at 32/39 while system and notification sit at 8/39.
     * An earlier 0.55 on top of the system stream landed at roughly 11% of
     * full scale, which was inaudible next to Xiaodi's own prompt.
     */
    private static final float VOLUME = 1.0f;

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
                    // Deliberately the media stream, not sonification.
                    // USAGE_ASSISTANCE_SONIFICATION maps to STREAM_SYSTEM,
                    // which this head unit keeps at 8 of 39 - about a quarter
                    // of the media stream, and far quieter than Xiaodi.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(2)
                    .setAudioAttributes(attrs).build();
        } else {
            pool = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
        }

        final Context app = ctx.getApplicationContext();
        final int[] res = { R.raw.sfx_listen, R.raw.sfx_ok, R.raw.sfx_fail };
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
                Log.i(TAG, "Feedback: " + res.length + " clips ready");
            }
        });
    }

    /**
     * Blip when the mic opens.
     *
     * <p>Delayed deliberately. Xiaodi is a PERSISTENT system app that still
     * receives the same steering-wheel broadcast, plays its own wake sound and
     * calls {@code setNaviMuteState(true)}, releasing it about 230 ms later.
     * A blip fired at t=0 lands inside that duck and is inaudible, which made
     * it sound as though only Xiaodi's SFX played. Waiting clears the duck.
     */
    public static void listening() { playDelayed(R.raw.sfx_listen, 400); }
    /** Same clip with no delay, for the in-app sound check. */
    public static void listeningNow() { play(R.raw.sfx_listen); }
    public static void ack()       { play(R.raw.sfx_ok); }
    public static void nack()      { play(R.raw.sfx_fail); }

    public static void forResult(Commands.Result r) {
        if (r == null || !r.success) nack(); else ack();
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
