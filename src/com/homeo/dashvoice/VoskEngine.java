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

    /** Hard cap on a single capture, even if the talker never stops. */
    private static final long MAX_LISTEN_MS = 7000;
    /** Stop once the recogniser emitted a final segment and silence follows. */
    private static final long TAIL_SILENCE_MS = 500;
    /**
     * Give up only if nothing at all was heard for this long. Generous on
     * purpose: Xiaodi still pops up and plays its own prompt on the same
     * button, which delays the talker by roughly a second, and an earlier
     * 2.2 s value cut the capture off before speech even started.
     */
    private static final long NO_SPEECH_MS = 4500;
    /** After speech was heard, this much quiet ends the utterance. */
    private static final long POST_SPEECH_SILENCE_MS = 700;
    /**
     * Once a partial result already spells a complete command, wait only this
     * long for it to change before acting on it. This is the main latency win:
     * "ac on" fires in roughly a third of a second instead of waiting out the
     * recogniser's own endpointer.
     */
    private static final long PARTIAL_COMMIT_MS = 300;
    /**
     * Blocks used to sample the ambient noise floor before speech can be
     * declared. 100 ms per block.
     */
    private static final int CALIBRATION_BLOCKS = 6;
    /**
     * Speech is declared when a block's mean amplitude exceeds the measured
     * floor by this factor. Relative rather than absolute, because the cabin
     * floor changes enormously with fan speed and road noise.
     */
    private static final float SPEECH_FACTOR = 2.5f;
    /** Never set the speech gate below this, to ignore tiny fluctuations. */
    private static final int SPEECH_FLOOR_MIN = 120;
    /**
     * Never set it above this either. The stock assistant plays its own prompt
     * over the first few hundred milliseconds of our capture, and averaging
     * that in measured a "floor" of 164, putting the gate at 410 — above the
     * 370 peak of the speech that followed, so nothing was ever heard.
     *
     * <p>The two failure modes are not symmetric. A gate set too low only
     * makes us listen slightly longer; a gate set too high loses the command
     * entirely. So the floor is taken as the quietest calibration block rather
     * than the mean, and capped here as a backstop.
     */
    private static final int SPEECH_GATE_MAX = 300;

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
            // The grammar includes dynamically discovered app names, and any
            // word outside the model vocabulary can make this throw. Losing
            // "open x" is far better than losing every command, so fall back to
            // the fixed table rather than failing the capture.
            try {
                rec = new Recognizer(model, (float) SAMPLE_RATE, Commands.grammarJson());
            } catch (Throwable t) {
                Log.w(TAG, "grammar with app names rejected (" + t.getMessage()
                        + "); falling back to commands only");
                rec = new Recognizer(model, (float) SAMPLE_RATE, Commands.grammarJsonNoApps());
            }
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

            boolean speechSeen = false;
            long lastLoudAt = 0;
            String lastPartial = "";
            long partialChangedAt = 0;
            String committed = "";

            // Ambient calibration: the cabin floor varies hugely with fan
            // speed, so the speech gate is derived from measurement rather
            // than a fixed constant. The quietest block is used, not the
            // mean, so a transient noise inside the window - typically the
            // stock assistant's own prompt - cannot inflate the gate above
            // the speech that follows it.
            int blocks = 0;
            int floorMin = Integer.MAX_VALUE;
            int speechGate = Integer.MAX_VALUE;   // until calibrated
            int peak = 0;

            while (listening) {
                long now = System.currentTimeMillis();
                if (now - started > MAX_LISTEN_MS) break;
                if (finalAt > 0 && now - finalAt > TAIL_SILENCE_MS) break;
                // Nothing heard at all - don't hold the mic for the full cap.
                if (!speechSeen && now - started > NO_SPEECH_MS) break;
                // Speech happened and then stopped: that's the end of the command.
                if (speechSeen && lastLoudAt > 0
                        && now - lastLoudAt > POST_SPEECH_SILENCE_MS) break;

                int n = audio.read(buf, 0, buf.length);
                if (n <= 0) continue;

                int amp = meanAmplitude(buf, n);
                if (amp > peak) peak = amp;

                if (blocks < CALIBRATION_BLOCKS) {
                    if (amp < floorMin) floorMin = amp;
                    blocks++;
                    if (blocks == CALIBRATION_BLOCKS) {
                        int gate = (int) (floorMin * SPEECH_FACTOR);
                        if (gate < SPEECH_FLOOR_MIN) gate = SPEECH_FLOOR_MIN;
                        if (gate > SPEECH_GATE_MAX)  gate = SPEECH_GATE_MAX;
                        speechGate = gate;
                        Log.i(TAG, "noise floor=" + floorMin + " speech gate=" + speechGate);
                    }
                } else if (amp >= speechGate) {
                    speechSeen = true;
                    lastLoudAt = System.currentTimeMillis();
                }

                if (rec.acceptWaveForm(buf, n)) {
                    String txt = textOf(rec.getResult());
                    if (!txt.isEmpty()) {
                        bestFinal = bestFinal.isEmpty() ? txt : bestFinal + " " + txt;
                        finalAt = System.currentTimeMillis();
                        Log.i(TAG, "segment: " + txt);
                    }
                } else {
                    String p = partialOf(rec.getPartialResult());
                    if (!p.isEmpty()) {
                        if (!p.equals(lastPartial)) {
                            lastPartial = p;
                            partialChangedAt = System.currentTimeMillis();
                            postPartial(l, p);
                        } else if (partialChangedAt > 0
                                && System.currentTimeMillis() - partialChangedAt > PARTIAL_COMMIT_MS
                                && Commands.endsWithCommand(p)) {
                            // Grammar is constrained, so a stable partial that
                            // already spells a command is as good as a final.
                            committed = p;
                            Log.i(TAG, "early commit: " + p);
                            break;
                        }
                    }
                }
            }

            audio.stop();
            // Amplitude summary makes mis-tuned gating obvious in the log
            // instead of showing up as a silent, empty result.
            Log.i(TAG, "capture done: peak=" + peak + " gate="
                    + (speechGate == Integer.MAX_VALUE ? "uncalibrated" : speechGate)
                    + " speechSeen=" + speechSeen
                    + " ms=" + (System.currentTimeMillis() - started));

            if (!committed.isEmpty()) {
                bestFinal = committed;
            } else {
                String tail = textOf(rec.getFinalResult());
                if (!tail.isEmpty()) {
                    bestFinal = bestFinal.isEmpty() ? tail : bestFinal + " " + tail;
                }
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

    /**
     * Mean absolute amplitude of a little-endian 16-bit PCM block.
     * Cheap proxy for RMS; used only to tell speech from cabin noise.
     */
    private static int meanAmplitude(byte[] buf, int bytes) {
        int samples = bytes / 2;
        if (samples == 0) return 0;
        long sum = 0;
        for (int i = 0; i + 1 < bytes; i += 2) {
            int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            sum += s < 0 ? -s : s;
        }
        return (int) (sum / samples);
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
