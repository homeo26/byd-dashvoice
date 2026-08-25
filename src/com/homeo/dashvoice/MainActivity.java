package com.homeo.dashvoice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
 * The model is loaded from the app's external files dir rather than bundled
 * in assets, so it can be pushed with adb and iterated on without rebuilding
 * a 60 MB APK:
 *
 *   adb push vosk-model-small-en-us-0.15 \
 *     /sdcard/Android/data/com.homeo.dashvoice/files/model
 */
public class MainActivity extends Activity implements VoskEngine.Listener {

    private static final String TAG = "DashVoice";
    private static final int REQ_MIC = 1;

    private VoskEngine engine;
    private Button talkBtn;
    private TextView heard;
    private TextView status;
    private TextView setupView;
    private Button accBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new VoskEngine();

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
        sub.setText("English voice control for the BYD climate system");
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

        // ---- enable-accessibility shortcut ----
        accBtn = new Button(this);
        accBtn.setText("Open Accessibility settings");
        accBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    toast("Could not open Accessibility settings");
                }
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(6);
        root.addView(accBtn, ap);

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

        // ---- manual test row: verifies the accessibility layer without speech ----
        TextView testHdr = new TextView(this);
        testHdr.setText("Test without speaking");
        testHdr.setTextSize(12f);
        testHdr.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams thp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thp.topMargin = dp(10);
        root.addView(testHdr, thp);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        // Reversible, harmless commands only.
        addTestBtn(testRow, "fan three");
        addTestBtn(testRow, "fan five");
        addTestBtn(testRow, "warmer");
        addTestBtn(testRow, "colder");
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trp.topMargin = dp(4);
        root.addView(testRow, trp);

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
        cmdHdr.setText("Recognised commands");
        cmdHdr.setTextSize(14f);
        cmdHdr.setAllCaps(true);
        cmdHdr.setTypeface(cmdHdr.getTypeface(), Typeface.BOLD);
        cmdHdr.setTextColor(Color.parseColor("#1565C0"));
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chp.topMargin = dp(16);
        root.addView(cmdHdr, chp);

        TextView cmds = new TextView(this);
        cmds.setTextSize(12f);
        cmds.setTextColor(Color.parseColor("#444444"));
        cmds.setText(TextUtils.join("   \u2022   ", Commands.phrases()));
        root.addView(cmds);

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

    /**
     * Also refresh on focus changes. Enabling the accessibility service
     * (from Settings, or over adb) does not necessarily trigger onResume if
     * this activity never left the foreground, which would otherwise leave
     * the checklist showing a stale [--].
     */
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
        boolean acc   = ClimateService.isRunning();
        boolean model = engine.isModelLoaded();
        File md = modelDir();

        StringBuilder sb = new StringBuilder();
        sb.append(mic   ? "[ok] " : "[--] ").append("microphone permission\n");
        sb.append(acc   ? "[ok] " : "[--] ").append("accessibility service enabled\n");
        sb.append(model ? "[ok] " : "[--] ").append("speech model loaded\n");
        if (!model) {
            sb.append("\nmodel expected at:\n  ").append(md.getAbsolutePath()).append("\n");
            sb.append("push it with:\n");
            sb.append("  adb push vosk-model-small-en-us-0.15 \\\n    ")
              .append(md.getAbsolutePath());
        }
        setupView.setText(sb.toString());

        accBtn.setVisibility(acc ? View.GONE : View.VISIBLE);

        boolean ready = mic && acc && model;
        talkBtn.setEnabled(ready);
        talkBtn.setBackgroundColor(ready
                ? Color.parseColor("#1565C0") : Color.parseColor("#9E9E9E"));
        if (!ready && status.getText().length() == 0) {
            status.setText("Complete the checklist above to enable voice control.");
        }
    }

    private void requestMicIfNeeded() {
        if (!micGranted()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
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

    /**
     * A button that runs a phrase through the exact same match+dispatch path
     * that speech uses, so the accessibility layer can be validated in
     * isolation from recognition.
     */
    private void addTestBtn(LinearLayout row, final String phrase) {
        Button b = new Button(this);
        b.setText(phrase);
        b.setTextSize(12f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ClimateService.isRunning()) {
                    append("accessibility service not running");
                    return;
                }
                heard.setText("\u201c" + phrase + "\u201d (manual)");
                append("manual: " + phrase);
                List<Commands.Cmd> m = Commands.matchAll(phrase);
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
    }

    @Override
    public void onPartial(String text) {
        if (!text.isEmpty()) heard.setText(text + " \u2026");
    }

    @Override
    public void onError(String message) {
        append("ERROR: " + message);
    }

    @Override
    public void onFinal(String text) {
        if (text == null || text.isEmpty()) {
            heard.setText("(nothing recognised)");
            append("no speech detected - try again, closer to the mic");
            return;
        }
        heard.setText("\u201c" + text + "\u201d");
        append("heard: " + text);

        List<Commands.Cmd> matches = Commands.matchAll(text);
        if (matches.isEmpty()) {
            append("no command matched");
            return;
        }
        dispatch(matches);
    }

    /** Run matched commands on a worker thread; UI updates posted back. */
    private void dispatch(final List<Commands.Cmd> matches) {
        final ClimateService svc = ClimateService.get();
        if (svc == null) {
            append("accessibility service not running");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                // The AC controls only exist while the AC screen is up.
                if (!svc.isAcForeground()) {
                    postAppend("opening climate screen\u2026");
                    svc.openAcScreen();
                    sleep(1200);
                }
                for (Commands.Cmd c : matches) {
                    ClimateService.Result r = Commands.execute(svc, c);
                    postAppend((r.success ? "  ok   " : "  FAIL ")
                            + c.phrase + " -> " + r.message);
                    sleep(250);
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
