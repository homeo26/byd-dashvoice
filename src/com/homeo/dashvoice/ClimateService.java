package com.homeo.dashvoice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.accessibilityservice.GestureDescription;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Drives the BYD air-conditioning UI on behalf of voice commands.
 *
 * Why an AccessibilityService instead of the car API:
 *   The AirConditioningManager binder (service "airconditioning") silently
 *   ignores writes from non-system UIDs. com.byd.airconditioning itself runs
 *   as android.uid.system, so when *it* calls the car API the call lands.
 *   We therefore drive its UI rather than the API.
 *
 * Why dispatchGesture instead of ACTION_CLICK:
 *   Most BYD AC controls report clickable="false" (they attach touch
 *   listeners, not click listeners), so performAction(ACTION_CLICK) is a
 *   no-op on them. We resolve the node by resource id, read its on-screen
 *   bounds, and synthesise a tap at the centre.
 *
 * Resource ids were dumped from a real Di2.1H / 4.0 UI unit.
 */
public class ClimateService extends AccessibilityService {

    private static final String TAG = "DashVoice";

    public static final String AC_PKG = "com.byd.airconditioning";
    /** Action that opens the fullscreen AC activity (found in its manifest). */
    public static final String AC_OPEN_ACTION = "OPEN_AIR_CONDITIONING";

    // ---- Verified resource ids (com.byd.airconditioning) ----
    public static final String ID_AC_POWER      = AC_PKG + ":id/front_ac_power_id";
    public static final String ID_COMPRESSOR    = AC_PKG + ":id/ac_compressor_id";
    public static final String ID_AUTO_MODE     = AC_PKG + ":id/control_mode_id";
    public static final String ID_MAX_COOLING   = AC_PKG + ":id/max_cooling_id";
    public static final String ID_FRONT_DEFROST = AC_PKG + ":id/front_defrost_id";
    public static final String ID_REAR_DEFROST  = AC_PKG + ":id/rear_defrost_id";
    public static final String ID_RECIRCULATE   = AC_PKG + ":id/cycle_mode_id";
    public static final String ID_VENTILATION   = AC_PKG + ":id/ventilation_id";
    public static final String ID_TEMP_UP       = AC_PKG + ":id/main_arrow_plus_img";
    public static final String ID_TEMP_DOWN     = AC_PKG + ":id/main_arrow_minus_img";
    public static final String ID_PASS_TEMP_UP  = AC_PKG + ":id/deputy_arrow_plus_img";
    public static final String ID_PASS_TEMP_DN  = AC_PKG + ":id/deputy_arrow_minus_img";
    public static final String ID_VENT_FACE     = AC_PKG + ":id/wind_mode_face_id";
    public static final String ID_VENT_FOOT     = AC_PKG + ":id/wind_mode_foot_id";
    public static final String ID_VENT_DEFROST  = AC_PKG + ":id/wind_mode_defrost_id";
    /** Endpoint buttons: a single tap jumps to the extreme, not one step. */
    public static final String ID_FAN_MAX       = AC_PKG + ":id/wind_max_id";
    public static final String ID_FAN_MIN       = AC_PKG + ":id/wind_min_id";
    /** The draggable fan-level track; positional taps select a level. */
    public static final String ID_FAN_TRACK     = AC_PKG + ":id/wind_level_id";

    public static final int MAX_FAN_LEVEL = 7;
    /** Fraction of ID_FAN_TRACK width corresponding to level 1 and level 7. */
    private static final double TRACK_F_MIN = 0.184;
    private static final double TRACK_F_MAX = 0.797;

    private static volatile ClimateService instance;

    public static boolean isRunning() { return instance != null; }
    public static ClimateService get() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "ClimateService connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        Log.i(TAG, "ClimateService unbound");
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { /* passive */ }
    @Override public void onInterrupt() { }

    /* ------------------------------------------------------------------ */

    /** True if the BYD AC app currently owns the foreground window. */
    public boolean isAcForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        CharSequence p = root.getPackageName();
        boolean r = p != null && AC_PKG.contentEquals(p);
        root.recycle();
        return r;
    }

    /** Bring the AC screen up so its nodes become reachable. */
    public void openAcScreen() {
        try {
            Intent i = new Intent(AC_OPEN_ACTION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "openAcScreen failed", t);
        }
    }

    /**
     * Resolve a control by resource id in the active window.
     * Caller must recycle the returned node.
     */
    private AccessibilityNodeInfo find(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByViewId(viewId);
            if (hits == null || hits.isEmpty()) return null;
            return hits.get(0);
        } catch (Throwable t) {
            Log.e(TAG, "find failed for " + viewId, t);
            return null;
        } finally {
            root.recycle();
        }
    }

    /** Synthesise a short tap at a screen coordinate. */
    private boolean tapAt(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0L, 60L);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(stroke).build();
        boolean ok = dispatchGesture(g, null, null);
        Log.i(TAG, "tapAt(" + x + "," + y + ") dispatched=" + ok);
        return ok;
    }

    /** Tap a control identified by resource id. */
    public Result tap(String viewId) {
        AccessibilityNodeInfo n = find(viewId);
        if (n == null) {
            return Result.fail("control not on screen: " + shortId(viewId));
        }
        try {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.isEmpty()) return Result.fail("control has no bounds: " + shortId(viewId));
            boolean ok = tapAt(b.centerX(), b.centerY());
            return ok ? Result.ok(shortId(viewId) + " tapped")
                      : Result.fail("gesture rejected for " + shortId(viewId));
        } finally {
            n.recycle();
        }
    }

    /**
     * Only tap when the control is not already in the desired state.
     *
     * BYD's controls are toggles, so a naive "AC on" would switch the AC
     * OFF when it was already on. The nodes expose selected=true when
     * active, so we read that first and no-op if it already matches.
     */
    public Result ensure(String viewId, boolean wantOn) {
        AccessibilityNodeInfo n = find(viewId);
        if (n == null) {
            return Result.fail("control not on screen: " + shortId(viewId));
        }
        boolean isOn;
        Rect b = new Rect();
        try {
            isOn = n.isSelected() || n.isChecked();
            n.getBoundsInScreen(b);
        } finally {
            n.recycle();
        }
        if (isOn == wantOn) {
            return Result.ok(shortId(viewId) + " already "
                    + (wantOn ? "on" : "off") + " - no action");
        }
        if (b.isEmpty()) return Result.fail("control has no bounds: " + shortId(viewId));
        boolean ok = tapAt(b.centerX(), b.centerY());
        return ok ? Result.ok(shortId(viewId) + " -> " + (wantOn ? "on" : "off"))
                  : Result.fail("gesture rejected for " + shortId(viewId));
    }

    /** Tap the same control several times (temperature steps). */
    public Result tapRepeat(final String viewId, int times) {
        Result last = Result.fail("no taps performed");
        for (int i = 0; i < times; i++) {
            last = tap(viewId);
            if (!last.success) return last;
            sleep(220);
        }
        return last;
    }

    /**
     * Set the fan to an absolute level, 1..7.
     *
     * wind_min_id / wind_max_id turned out to be endpoint buttons ("go to
     * minimum" / "go to maximum"), not increment/decrement - a single tap on
     * wind_max_id jumps straight from 1 to 7. Intermediate levels therefore
     * need a positional tap on the wind_level_id track.
     *
     * Calibrated against the live UI on a Di2.1H unit:
     *   x=520 -> 1,  x=620 -> 3,  x=716 -> 4,  x=820 -> 6,  x=900 -> 7
     * As a fraction of wind_level_id's width those are 0.184 .. 0.797, so we
     * interpolate within the node's measured bounds rather than hardcoding
     * pixels (keeps working if the layout shifts).
     */
    public Result setFanLevel(int level) {
        if (level < 1) level = 1;
        if (level > MAX_FAN_LEVEL) level = MAX_FAN_LEVEL;

        AccessibilityNodeInfo n = find(ID_FAN_TRACK);
        if (n == null) return Result.fail("fan slider not on screen");
        Rect b = new Rect();
        try {
            n.getBoundsInScreen(b);
        } finally {
            n.recycle();
        }
        if (b.isEmpty()) return Result.fail("fan slider has no bounds");

        double f = TRACK_F_MIN
                + (level - 1) * (TRACK_F_MAX - TRACK_F_MIN) / (MAX_FAN_LEVEL - 1);
        int x = b.left + (int) Math.round(f * b.width());
        int y = b.centerY();

        boolean ok = tapAt(x, y);
        return ok ? Result.ok("fan level -> " + level)
                  : Result.fail("gesture rejected for fan slider");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String shortId(String viewId) {
        int i = viewId.indexOf("/");
        return i >= 0 ? viewId.substring(i + 1) : viewId;
    }

    /** Simple success/message pair so the UI can report honestly. */
    public static class Result {
        public final boolean success;
        public final String message;
        private Result(boolean s, String m) { success = s; message = m; }
        public static Result ok(String m)   { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }
}
