package com.homeo.dashvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

/**
 * Foreground service that listens for the steering-wheel mic broadcast
 * ({@code android.intent.action.MEDIA_VOICE}) and runs the same voice
 * pipeline the HOLD TO TALK button triggers.
 *
 * <p>Runs whether or not MainActivity is open. The receiver is registered
 * dynamically because MEDIA_VOICE is delivered as a parallel broadcast
 * on this firmware — Xiaodi also uses a dynamic registration for it.
 *
 * <p><b>Mic contention:</b> the unit reports {@code maxActiveCount: 1} on
 * the input device. If Xiaodi grabs the mic first we lose. Options:
 *   1. Race by calling {@code AudioRecord.startRecording()} synchronously
 *      inside {@code onReceive} — whoever wins wins.
 *   2. Disable Xiaodi (Settings → Apps → 你好小迪 → Disable) so the
 *      broadcast reaches only DashVoice.
 */
public class MicKeyService extends Service implements VoskEngine.Listener {

    private static final String TAG = "DashVoice";
    private static final String CH_ID   = "dashvoice.mickey";
    private static final int    NOTIF_ID = 1701;

    /** The broadcast the framework's MediaKeyHandler emits for keycode 304. */
    private static final String ACTION_MIC = "android.intent.action.MEDIA_VOICE";

    /** Preferences flag set by MainActivity to enable/disable hook. */
    static final String PREFS      = "dashvoice";
    static final String KEY_MIC_HOOK = "steering_mic_hook";
    /**
     * Default for the hook when no choice has been saved yet. On by
     * default: the steering-wheel button is the whole point of the app,
     * and a fresh install should work without hunting for a toggle.
     */
    static final boolean HOOK_DEFAULT = true;

    private VoskEngine engine;
    private BydAcApi ac;
    private BydBodyworkApi body;
    private BroadcastReceiver micReceiver;

    /** Latest recognised text — dumped to log; UI can subscribe later. */
    private volatile String lastHeard = "";

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        Feedback.init(this);
        Commands.attach(this);

        engine = new VoskEngine();
        ac = BydAcApi.tryCreate(this);
        body = BydBodyworkApi.tryCreate(this);

        File modelDir = new File(getExternalFilesDir(null), "model");
        boolean ok = engine.loadModel(modelDir);
        Log.i(TAG, "MicKeyService: model loaded=" + ok + " ac=" + (ac!=null) + " body=" + (body!=null));

        // Register on ALL priorities we can — SYSTEM_HIGH_PRIORITY on dynamic
        // receivers is advisory, but doesn't hurt.
        micReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (ACTION_MIC.equals(intent.getAction())) onMicButton();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_MIC);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(micReceiver, f);
        Log.i(TAG, "MicKeyService: MEDIA_VOICE receiver registered");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "MicKeyService: stopping");
        try { if (micReceiver != null) unregisterReceiver(micReceiver); } catch (Throwable ignored) {}
        if (engine != null) engine.release();
        super.onDestroy();
    }

    /* ------------------ mic button trigger ------------------ */

    private void onMicButton() {
        Log.i(TAG, "MicKeyService: MEDIA_VOICE received");
        if (engine == null || !engine.isModelLoaded()) {
            Log.w(TAG, "  model not ready");
            return;
        }
        if (engine.isListening()) {
            Log.i(TAG, "  already listening, ignoring second trigger");
            return;
        }
        // Start listening immediately — same call MainActivity uses.
        engine.startListening(this);
    }

    /* ------------------ Vosk listener callbacks ------------------ */

    @Override public void onStateChange(boolean listening) {
        Log.i(TAG, "MicKeyService: listening=" + listening);
        updateNotification(listening ? "Listening…" : "Ready");
        // Only a blip on open — a second tone on close just adds chatter.
        if (listening) Feedback.listening();
    }

    @Override public void onPartial(String text) {
        // Log partials sparingly
        if (!text.isEmpty() && !text.equals(lastHeard)) {
            lastHeard = text;
            Log.v(TAG, "  partial: " + text);
        }
    }

    @Override public void onError(String message) {
        Log.e(TAG, "MicKeyService: recogniser error: " + message);
    }

    @Override public void onFinal(String text) {
        Log.i(TAG, "MicKeyService onFinal: '" + text + "'");
        if (text == null || text.isEmpty()) {
            Feedback.unheard();
            updateNotification("Nothing recognised");
            updateNotificationLater();
            return;
        }
        java.util.List<Commands.Entry> matches = Commands.matchAll(text);
        if (matches.isEmpty()) {
            Feedback.unheard();
            updateNotification("No command matched: " + text);
            updateNotificationLater();
            return;
        }
        // Dispatch on a worker thread — MCU write throttling matters here too.
        final BydAcApi acRef = ac;
        final BydBodyworkApi bodyRef = body;
        new Thread(new Runnable() {
            @Override public void run() {
                for (Commands.Entry c : matches) {
                    Commands.Result r = Commands.execute(acRef, bodyRef, c);
                    Feedback.forResult(r);
                    Log.i(TAG, "  " + (r.success ? "ok" : "FAIL") + " " + c.phrase + " -> " + r.message);
                    try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                }
                updateNotificationLater();
            }
        }, "mic-dispatch").start();
    }

    /* ------------------ notification ------------------ */

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "DashVoice mic hook", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Listens for the steering-wheel mic button");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification("Ready");
        startForeground(NOTIF_ID, n);
    }

    private Notification buildNotification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("BYD DashVoice")
                .setContentText(status)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String status) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(status));
        } catch (Throwable ignored) {}
    }

    private void updateNotificationLater() {
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { updateNotification("Ready"); }
        }, 2500);
    }

    /* ------------------ start helper ------------------ */

    public static void ensureRunning(Context ctx) {
        Intent i = new Intent(ctx, MicKeyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, MicKeyService.class));
    }
}
