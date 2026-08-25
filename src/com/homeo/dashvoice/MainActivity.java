package com.homeo.dashvoice;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Push-to-talk English climate control for BYD DiLink.
 *
 * <p>Backend: {@link BydAcApi}, which reaches
 * {@code android.hardware.bydauto.ac.BYDAutoAcDevice} via reflection and a
 * ContextWrapper that no-ops BYDAUTO_* permission checks. No root, no
 * accessibility service, no UI tapping.
 *
 * <p>The Vosk speech model is loaded from the app's external files dir so it
 * can be pushed with adb without rebuilding a 60 MB APK:
 * <pre>
 *   adb push vosk-model-small-en-us-0.15 \
 *     /sdcard/Android/data/com.homeo.dashvoice/files/model
 * </pre>
 */
public class MainActivity extends Activity implements VoskEngine.Listener {

    private static final String TAG = "DashVoice";
    private static final int REQ_MIC = 1;

    private VoskEngine engine;
    private BydAcApi ac;
    private BydBodyworkApi body;
    private Button talkBtn;
    private TextView heard;
    private TextView status;
    private TextView setupView;

    /**
     * BYDAUTO permissions we request. All are dangerous-level on this build
     * and grantable at runtime. If the user declines, the corresponding API
     * simply isn't available and the setup checklist will show it as missing.
     */
    private static final String[] BYD_PERMS = {
            "android.permission.BYDAUTO_AC_COMMON",
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_LIGHT_COMMON",
            "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
            "android.permission.BYDAUTO_ENERGY_COMMON",
            "android.permission.BYDAUTO_INSTRUMENT_COMMON",
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new VoskEngine();
        // APIs are (re)acquired after permissions are granted / on resume.
        acquireApis();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        // ---- title ----
        TextView title = new TextView(this);
        title.setText("BYD DashVoice");
        title.setTextSize(22f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1565C0"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("English voice control — direct BYDAuto API, no root");
        sub.setTextSize(12f);
        sub.setTextColor(Color.parseColor("#666666"));
        root.addView(sub);

        // ---- setup checklist ----
        setupView = new TextView(this);
        setupView.setTextSize(13f);
        setupView.setTypeface(Typeface.MONOSPACE);
        setupView.setPadding(dp(10), dp(10), dp(10), dp(10));
        setupView.setBackground(rounded(0xFFBDBDBD, 0xFFF5F5F5));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        root.addView(setupView, sp);

        // ---- push to talk ----
        talkBtn = new Button(this);
        talkBtn.setText("HOLD TO TALK");
        talkBtn.setTextSize(20f);
        talkBtn.setTypeface(talkBtn.getTypeface(), Typeface.BOLD);
        talkBtn.setBackgroundColor(Color.parseColor("#1565C0"));
        talkBtn.setTextColor(Color.WHITE);
        talkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTalk(); }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        tp.topMargin = dp(12);
        root.addView(talkBtn, tp);

        // ---- Mic-button hook toggle (starts/stops MicKeyService) ----
        TextView hookHdr = new TextView(this);
        hookHdr.setText("Steering-wheel mic button");
        hookHdr.setTextSize(12f);
        hookHdr.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams hhp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hhp.topMargin = dp(10);
        root.addView(hookHdr, hhp);

        final Button hookBtn = new Button(this);
        final SharedPreferences prefs = getSharedPreferences(MicKeyService.PREFS, MODE_PRIVATE);
        Feedback.init(this);
        final boolean[] hookOn = new boolean[]{ prefs.getBoolean(MicKeyService.KEY_MIC_HOOK, false) };
        // If the hook was enabled previously, make sure the service is alive.
        // A force-stop (or our own reinstall) kills it, and without this it
        // would stay dead until the next reboot or a manual re-toggle.
        if (hookOn[0]) MicKeyService.ensureRunning(this);
        hookBtn.setText(hookOn[0]
                ? "Mic-button hook: ON (tap to disable)"
                : "Enable mic-button hook (Xiaodi will race)");
        hookBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hookOn[0] = !hookOn[0];
                prefs.edit().putBoolean(MicKeyService.KEY_MIC_HOOK, hookOn[0]).apply();
                if (hookOn[0]) {
                    MicKeyService.ensureRunning(MainActivity.this);
                    hookBtn.setText("Mic-button hook: ON (tap to disable)");
                    toast("Mic-button hook enabled");
                } else {
                    MicKeyService.stop(MainActivity.this);
                    hookBtn.setText("Enable mic-button hook (Xiaodi will race)");
                    toast("Mic-button hook disabled");
                }
            }
        });
        LinearLayout.LayoutParams hbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbp.topMargin = dp(4);
        root.addView(hookBtn, hbp);

        // ---- sound check ----
        // The command chips further down cover manual dispatch, so the old
        // duplicate test-button grid is gone. What is still worth testing on
        // its own is audio, because the feedback tones compete with Xiaodi.
        TextView sndHdr = new TextView(this);
        sndHdr.setText("Sound check");
        sndHdr.setTextSize(12f);
        sndHdr.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shp.topMargin = dp(10);
        root.addView(sndHdr, shp);

        LinearLayout sndRow = new LinearLayout(this);
        sndRow.setOrientation(LinearLayout.HORIZONTAL);
        addSoundBtn(sndRow, "mic opened", 0);
        addSoundBtn(sndRow, "command ok", 1);
        addSoundBtn(sndRow, "command failed", 2);
        addRow(root, sndRow);

        // ---- heard / status ----
        heard = new TextView(this);
        heard.setTextSize(18f);
        heard.setTextColor(Color.parseColor("#111111"));
        heard.setText("\u2014");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(10);
        root.addView(heard, hp);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Color.parseColor("#333333"));
        status.setTextIsSelectable(true);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(6);
        root.addView(status, stp);

        // ---- command reference ----
        TextView cmdHdr = new TextView(this);
        cmdHdr.setText("What you can say");
        cmdHdr.setTextSize(14f);
        cmdHdr.setAllCaps(true);
        cmdHdr.setTypeface(cmdHdr.getTypeface(), Typeface.BOLD);
        cmdHdr.setTextColor(Color.parseColor("#1565C0"));
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chp.topMargin = dp(16);
        root.addView(cmdHdr, chp);

        TextView cmdSub = new TextView(this);
        cmdSub.setText("Press the steering-wheel mic button, or HOLD TO TALK above. "
                + "Tap any phrase to run it without speaking.");
        cmdSub.setTextSize(11f);
        cmdSub.setTextColor(Color.parseColor("#777777"));
        root.addView(cmdSub);

        for (CommandReference.Group g : CommandReference.GROUPS) {
            addCommandGroup(root, g);
        }

        scroll.addView(root);
        setContentView(scroll);

        requestMicIfNeeded();
        loadModelAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshSetup();
    }

    /* ---------------- setup state ---------------- */

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private File modelDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        return new File(base, "model");
    }

    private void refreshSetup() {
        boolean mic   = micGranted();
        boolean model = engine.isModelLoaded();
        boolean api   = ac != null;
        boolean bodyOk= body != null;
        File md = modelDir();

        StringBuilder sb = new StringBuilder();
        sb.append(mic     ? "[ok] " : "[--] ").append("microphone permission\n");
        sb.append(model   ? "[ok] " : "[--] ").append("speech model loaded\n");
        sb.append(api     ? "[ok] " : "[--] ").append("BYDAuto AC device (climate)\n");
        sb.append(bodyOk  ? "[ok] " : "[--] ").append("BYDAuto bodywork device (windows)\n");
        if (!model) {
            sb.append("\nspeech model expected at:\n  ")
              .append(md.getAbsolutePath()).append("\n");
            sb.append("push it with:\n");
            sb.append("  adb push vosk-model-small-en-us-0.15/. \\\n    ")
              .append(md.getAbsolutePath()).append("/");
        }
        if (!api || !bodyOk) {
            sb.append("\ntap the app once and accept the BYDAuto permission dialog");
            sb.append("\nif you missed it, uninstall and reinstall (or grant via adb):");
            sb.append("\n  adb shell pm grant com.homeo.dashvoice \\\n    android.permission.BYDAUTO_AC_COMMON");
        }
        setupView.setText(sb.toString());

        boolean ready = mic && model && api;
        talkBtn.setEnabled(ready);
        talkBtn.setBackgroundColor(ready
                ? Color.parseColor("#1565C0") : Color.parseColor("#9E9E9E"));
    }

    private void requestMicIfNeeded() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (!micGranted()) need.add(Manifest.permission.RECORD_AUDIO);
        for (String p : BYD_PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        if (!need.isEmpty()) {
            Log.i(TAG, "requesting " + need.size() + " permissions");
            requestPermissions(need.toArray(new String[0]), REQ_MIC);
        }
    }

    private void acquireApis() {
        // Only try to instantiate if the required permission is granted.
        // Otherwise getInstance() throws a SecurityException that clutters logs.
        if (checkSelfPermission("android.permission.BYDAUTO_AC_COMMON")
                == PackageManager.PERMISSION_GRANTED) {
            ac = BydAcApi.tryCreate(this);
        }
        if (checkSelfPermission("android.permission.BYDAUTO_BODYWORK_COMMON")
                == PackageManager.PERMISSION_GRANTED) {
            body = BydBodyworkApi.tryCreate(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        Log.i(TAG, "permission result: " + java.util.Arrays.toString(perms));
        acquireApis();
        refreshSetup();
    }

    private void loadModelAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = engine.loadModel(modelDir());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) append("model not loaded - see checklist");
                        refreshSetup();
                    }
                });
            }
        }, "model-load").start();
    }

    /* ---------------- test buttons ---------------- */

    private void addRow(LinearLayout root, LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        root.addView(row, lp);
    }

    private void addTestBtn(LinearLayout row, String label, final String phrase) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ac == null) { toast("AC API not available"); return; }
                heard.setText("\u201c" + phrase + "\u201d (manual)");
                append("manual: " + phrase);
                List<Commands.Entry> m = Commands.matchAll(phrase);
                if (m.isEmpty()) { append("no command matched"); return; }
                dispatch(m);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(b, lp);
    }

    /* ---------------- talk ---------------- */

    private void onTalk() {
        if (engine.isListening()) { engine.stopListening(); return; }
        status.setText("");
        heard.setText("listening\u2026");
        engine.startListening(this);
    }

    @Override
    public void onStateChange(boolean listening) {
        talkBtn.setText(listening ? "LISTENING\u2026 (tap to stop)" : "HOLD TO TALK");
        talkBtn.setBackgroundColor(listening
                ? Color.parseColor("#C62828") : Color.parseColor("#1565C0"));
        if (listening) Feedback.listening();
    }

    @Override
    public void onPartial(String text) {
        if (!text.isEmpty()) heard.setText(text + " \u2026");
    }

    @Override
    public void onError(String message) {
        Feedback.nack();
        append("ERROR: " + message);
    }

    @Override
    public void onFinal(String text) {
        Log.i(TAG, "onFinal: '" + text + "'");
        if (text == null || text.isEmpty()) {
            Feedback.nack();
            heard.setText("(nothing recognised)");
            append("no speech detected - try again, closer to the mic");
            return;
        }
        heard.setText("\u201c" + text + "\u201d");
        append("heard: " + text);
        if (ac == null) { Feedback.nack(); append("AC API not available"); return; }
        List<Commands.Entry> matches = Commands.matchAll(text);
        if (matches.isEmpty()) { Feedback.nack(); append("no command matched"); return; }
        dispatch(matches);
    }

    /** Plays one of the three feedback clips so audibility can be checked. */
    private void addSoundBtn(LinearLayout row, String label, final int which) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                switch (which) {
                    case 0: Feedback.listeningNow(); break;
                    case 1: Feedback.ack();  break;
                    default: Feedback.nack(); break;
                }
            }
        });
        row.addView(b, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /**
     * Renders one command group as a card: a title with a status badge, the
     * phrases as tappable chips, and an optional explanatory note.
     *
     * <p>Chips run the phrase through the same dispatch path as speech, so the
     * screen doubles as a manual control surface and a way to prove a command
     * works when recognition is in doubt.
     */
    private void addCommandGroup(LinearLayout root, final CommandReference.Group g) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(Color.parseColor(
                g.status == CommandReference.Status.WORKS ? "#FAFAFA" : "#F5F0E8"));
        bg.setStroke(dp(1), Color.parseColor(
                g.status == CommandReference.Status.WORKS ? "#E0E0E0" : "#D8C9A8"));
        card.setBackground(bg);

        // Title line, with a badge when the group is not usable.
        TextView title = new TextView(this);
        String badge;
        switch (g.status) {
            case BLOCKED: badge = "   \u2014 car refuses"; break;
            case ABSENT:  badge = "   \u2014 not fitted";  break;
            default:      badge = "";
        }
        title.setText(g.title + badge);
        title.setTextSize(13f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor(
                g.status == CommandReference.Status.WORKS ? "#212121" : "#8D6E63"));
        card.addView(title);

        // Phrases as chips, wrapped across rows of three.
        LinearLayout row = null;
        for (int i = 0; i < g.phrases.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(6);
                card.addView(row, rp);
            }
            row.addView(buildChip(g.phrases[i], g.status), chipParams());
        }

        if (g.note != null) {
            TextView note = new TextView(this);
            note.setText(g.note);
            note.setTextSize(10.5f);
            note.setTextColor(Color.parseColor("#8A8A8A"));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(6);
            card.addView(note, np);
        }

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        root.addView(card, cp);
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(6);
        return p;
    }

    /** A tappable phrase chip that runs the phrase through voice dispatch. */
    private View buildChip(final String phrase, CommandReference.Status status) {
        TextView chip = new TextView(this);
        chip.setText("\u201c" + phrase + "\u201d");
        chip.setTextSize(11.5f);
        chip.setPadding(dp(8), dp(7), dp(8), dp(7));
        chip.setMaxLines(2);
        chip.setTextColor(Color.parseColor(
                status == CommandReference.Status.WORKS ? "#0D47A1" : "#9E9E9E"));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor(
                status == CommandReference.Status.WORKS ? "#E8F0FE" : "#EEEEEE"));
        chip.setBackground(bg);

        if (status == CommandReference.Status.ABSENT) {
            chip.setEnabled(false);
        } else {
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    heard.setText("\u201c" + phrase + "\u201d  (tapped)");
                    append("run: " + phrase);
                    dispatch(Commands.matchAll(phrase));
                }
            });
        }
        return chip;
    }

    /** Run matched commands on a worker thread; UI updates posted back. */
    private void dispatch(final List<Commands.Entry> matches) {
        if (matches == null || matches.isEmpty()) {
            Feedback.nack();
            return;
        }
        final BydAcApi acRef = ac;
        final BydBodyworkApi bodyRef = body;
        new Thread(new Runnable() {
            @Override public void run() {
                for (Commands.Entry c : matches) {
                    Commands.Result r = Commands.execute(acRef, bodyRef, c);
                    Feedback.forResult(r);
                    postAppend((r.success ? "  ok   " : "  FAIL ")
                            + c.phrase + " -> " + r.message);
                    sleep(350);   // MCU throttles rapid writes
                }
            }
        }, "dispatch").start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /* ---------------- helpers ---------------- */

    private void append(String s) {
        Log.i(TAG, s);
        status.append(s + "\n");
    }

    private void postAppend(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { append(s); } });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable rounded(int stroke, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(6));
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.release();
        super.onDestroy();
    }
}
