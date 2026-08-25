package com.homeo.dashvoice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.LogLevel;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;

/**
 * Offline English recognition for car commands.
 *
 * Deliberately does NOT use org.vosk.android.SpeechService. That class
 * hardcodes MediaRecorder.AudioSource.VOICE_RECOGNITION (verified in its
 * bytecode: bipush 6), and on this BYD head unit that source delivers
 * ~81% of its energy below 200 Hz - AC fan rumble - and transcribes to
 * nothing. AudioSource.DEFAULT is the only source on this unit that yields
 * intelligible speech (measured: 7/7 words at 0.95 mean confidence).
 *
 * So we run our own capture loop and feed bytes to Recognizer directly.
 */
public class VoskEngine {

    private static final String TAG = "DashVoice";

    public static final int SAMPLE_RATE  = 16000;   // Vosk native, and the
                                                    // unit's native capture rate
    public static final int AUDIO_SOURCE = MediaRecorder.AudioSource.DEFAULT;

    /** Stop listening after this long even if no end-of-utterance is seen. */
    private static final long MAX_LISTEN_MS = 6000;
    /** Stop early once we have a final result and this much silence follows. */
    private static final long TAIL_SILENCE_MS = 600;

    public interface Listener {
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
        void onStateChange(boolean listening);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Model model;
    private volatile boolean listening = false;
    private Thread worker;

    /** Load the acoustic model from disk. Safe to call once at startup. */
    public boolean loadModel(File modelDir) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable t) {
            Log.w(TAG, "setLogLevel failed (non-fatal)", t);
        }
        if (modelDir == null || !modelDir.isDirectory()) {
            Log.e(TAG, "model dir missing: " + modelDir);
            return false;
        }
        // sanity: a Vosk model always has am/ and conf/
        File am = new File(modelDir, "am");
        File conf = new File(modelDir, "conf");
        if (!am.exists() || !conf.exists()) {
            Log.e(TAG, "not a vosk model (no am/ or conf/): " + modelDir);
            return false;
        }
        try {
            model = new Model(modelDir.getAbsolutePath());
            Log.i(TAG, "model loaded: " + modelDir.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "model load failed", t);
            return false;
        }
    }

    public boolean isModelLoaded() { return model != null; }
    public boolean isListening()   { return listening; }

    /** Begin a single push-to-talk capture. */
    public void startListening(final Listener l) {
        if (listening) { return; }
        if (model == null) { l.onError("model not loaded"); return; }

        listening = true;
        main.post(new Runnable() { public void run() { l.onStateChange(true); } });

        worker = new Thread(new Runnable() {
            @Override public void run() { captureLoop(l); }
        }, "vosk-capture");
        worker.start();
    }

    public void stopListening() { listening = false; }

    private void captureLoop(final Listener l) {
        Recognizer rec = null;
        AudioRecord audio = null;
        String bestFinal = "";
        try {
            rec = new Recognizer(model, (float) SAMPLE_RATE, Commands.grammarJson());
            rec.setWords(true);

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) { post(l, "getMinBufferSize failed"); return; }
            int bufBytes = Math.max(minBuf * 2, SAMPLE_RATE);   // ~0.5 s

            audio = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);

            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                post(l, "microphone unavailable (in use by another app?)");
                return;
            }

            audio.startRecording();
            Log.i(TAG, "listening (source=DEFAULT, 16k mono)");

            byte[] buf = new byte[3200];               // 100 ms
            long started = System.currentTimeMillis();
            long finalAt = 0;

            while (listening) {
                long now = System.currentTimeMillis();
                if (now - started > MAX_LISTEN_MS) break;
                if (finalAt > 0 && now - finalAt > TAIL_SILENCE_MS) break;

                int n = audio.read(buf, 0, buf.length);
                if (n <= 0) continue;

                if (rec.acceptWaveForm(buf, n)) {
                    String txt = textOf(rec.getResult());
                    if (!txt.isEmpty()) {
                        bestFinal = bestFinal.isEmpty() ? txt : bestFinal + " " + txt;
                        finalAt = System.currentTimeMillis();
                        Log.i(TAG, "segment: " + txt);
                    }
                } else {
                    String p = partialOf(rec.getPartialResult());
                    if (!p.isEmpty()) postPartial(l, p);
                }
            }

            audio.stop();
            String tail = textOf(rec.getFinalResult());
            if (!tail.isEmpty()) {
                bestFinal = bestFinal.isEmpty() ? tail : bestFinal + " " + tail;
            }

        } catch (Throwable t) {
            Log.e(TAG, "capture failed", t);
            post(l, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (audio != null) {
                try { audio.release(); } catch (Throwable ignored) {}
            }
            if (rec != null) {
                try { rec.close(); } catch (Throwable ignored) {}
            }
            listening = false;
            final String result = bestFinal.trim();
            main.post(new Runnable() {
                @Override public void run() {
                    l.onStateChange(false);
                    l.onFinal(result);
                }
            });
        }
    }

    private static String textOf(String json) {
        try { return new JSONObject(json).optString("text", "").trim(); }
        catch (Throwable t) { return ""; }
    }

    private static String partialOf(String json) {
        try { return new JSONObject(json).optString("partial", "").trim(); }
        catch (Throwable t) { return ""; }
    }

    private void post(final Listener l, final String msg) {
        main.post(new Runnable() { public void run() { l.onError(msg); } });
    }

    private void postPartial(final Listener l, final String txt) {
        main.post(new Runnable() { public void run() { l.onPartial(txt); } });
    }

    public void release() {
        listening = false;
        if (model != null) {
            try { model.close(); } catch (Throwable ignored) {}
            model = null;
        }
    }
}
