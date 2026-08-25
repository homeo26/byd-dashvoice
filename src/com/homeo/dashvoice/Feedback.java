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

    /** Fixed playback level, 0..1. Tuned to sit under the AC fan without shouting. */
    private static final float VOLUME = 0.55f;

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
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(2)
                    .setAudioAttributes(attrs).build();
        } else {
            pool = new SoundPool(2, AudioManager.STREAM_NOTIFICATION, 0);
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

    public static void listening() { play(R.raw.sfx_listen); }
    public static void ack()       { play(R.raw.sfx_ok); }
    public static void nack()      { play(R.raw.sfx_fail); }

    public static void forResult(Commands.Result r) {
        if (r == null || !r.success) nack(); else ack();
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
