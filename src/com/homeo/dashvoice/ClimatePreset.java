package com.homeo.dashvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * A saved climate configuration — "my preset" — captured from the car's
 * current state and re-applied on request.
 *
 * <p>Only the settings worth restoring are stored. Transient things such as
 * max-cooling or defrost are deliberately excluded: they are momentary actions
 * rather than a comfort configuration, and restoring them would be surprising.
 *
 * <p><b>Sentinel handling.</b> The framework reports unavailable values in
 * several different ways, and storing any of them would corrupt the preset:
 * <ul>
 *   <li>{@code Integer.MIN_VALUE} — our own reflection failure marker</li>
 *   <li>{@code 65535} / {@code -2147482645} — the framework's own "not
 *       available" sentinels, seen on window and sunroof getters</li>
 *   <li>a temperature of 17 can legitimately mean 17 degrees, but is also the
 *       "Lo" sentinel, so it is accepted only inside the valid range</li>
 * </ul>
 * Each field is validated on capture and skipped on apply if unknown, so a
 * partially readable car still yields a usable preset.
 */
final class ClimatePreset {

    private static final String TAG = "DashVoice";

    private static final String K_SAVED  = "preset_saved";
    private static final String K_START  = "preset_start";
    private static final String K_FAN    = "preset_fan";
    private static final String K_TEMP_D = "preset_temp_driver";
    private static final String K_TEMP_P = "preset_temp_passenger";
    private static final String K_WIND   = "preset_wind_mode";
    private static final String K_CYCLE  = "preset_cycle";
    private static final String K_COMP   = "preset_compressor";
    private static final String K_MODE   = "preset_control_mode";

    /** Marks a field as not known / not to be applied. */
    static final int UNKNOWN = Integer.MIN_VALUE;

    boolean saved;
    int start        = UNKNOWN;   // 1 = AC running
    int fan          = UNKNOWN;   // 1..7
    int tempDriver   = UNKNOWN;   // 17..30
    int tempPassenger= UNKNOWN;
    int windMode     = UNKNOWN;   // BydAcApi.WIND_*
    int cycleMode    = UNKNOWN;   // recirculate / fresh
    int compressor   = UNKNOWN;
    int controlMode  = UNKNOWN;   // auto / manual

    /* ---------------- validation ---------------- */

    /** True when a getter returned something real rather than a sentinel. */
    private static boolean plausible(int v) {
        return v != UNKNOWN && v != 65535 && v != -2147482645 && v >= 0;
    }
    private static int clean(int v)      { return plausible(v) ? v : UNKNOWN; }
    private static int cleanTemp(int v)  {
        return (v >= BydAcApi.MIN_TEMP_C && v <= BydAcApi.MAX_TEMP_C) ? v : UNKNOWN;
    }
    private static int cleanFan(int v)   {
        return (v >= BydAcApi.MIN_FAN && v <= BydAcApi.MAX_FAN) ? v : UNKNOWN;
    }

    /* ---------------- capture ---------------- */

    /** Read the car's current configuration. */
    static ClimatePreset capture(BydAcApi ac) {
        ClimatePreset p = new ClimatePreset();
        if (ac == null) return p;
        p.start         = clean(ac.getStartState());
        p.fan           = cleanFan(ac.getWindLevel());
        p.tempDriver    = cleanTemp(ac.getTemp(BydAcApi.ZONE_DRIVER));
        p.tempPassenger = cleanTemp(ac.getTemp(BydAcApi.ZONE_PASSENGER));
        p.windMode      = clean(ac.getWindMode());
        p.cycleMode     = clean(ac.getCycleMode());
        p.compressor    = clean(ac.getCompressor());
        p.controlMode   = clean(ac.getControlMode());
        p.saved         = p.hasAnything();
        return p;
    }

    boolean hasAnything() {
        return start != UNKNOWN || fan != UNKNOWN || tempDriver != UNKNOWN
            || windMode != UNKNOWN || cycleMode != UNKNOWN
            || compressor != UNKNOWN || controlMode != UNKNOWN;
    }

    /* ---------------- persistence ---------------- */

    void save(Context ctx) {
        SharedPreferences.Editor e =
                ctx.getSharedPreferences(MicKeyService.PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(K_SAVED, saved)
         .putInt(K_START, start).putInt(K_FAN, fan)
         .putInt(K_TEMP_D, tempDriver).putInt(K_TEMP_P, tempPassenger)
         .putInt(K_WIND, windMode).putInt(K_CYCLE, cycleMode)
         .putInt(K_COMP, compressor).putInt(K_MODE, controlMode)
         .apply();
        Log.i(TAG, "preset saved: " + describe());
    }

    static ClimatePreset load(Context ctx) {
        SharedPreferences s =
                ctx.getSharedPreferences(MicKeyService.PREFS, Context.MODE_PRIVATE);
        ClimatePreset p = new ClimatePreset();
        p.saved         = s.getBoolean(K_SAVED, false);
        p.start         = s.getInt(K_START,  UNKNOWN);
        p.fan           = s.getInt(K_FAN,    UNKNOWN);
        p.tempDriver    = s.getInt(K_TEMP_D, UNKNOWN);
        p.tempPassenger = s.getInt(K_TEMP_P, UNKNOWN);
        p.windMode      = s.getInt(K_WIND,   UNKNOWN);
        p.cycleMode     = s.getInt(K_CYCLE,  UNKNOWN);
        p.compressor    = s.getInt(K_COMP,   UNKNOWN);
        p.controlMode   = s.getInt(K_MODE,   UNKNOWN);
        return p;
    }

    static void clear(Context ctx) {
        ctx.getSharedPreferences(MicKeyService.PREFS, Context.MODE_PRIVATE)
           .edit().putBoolean(K_SAVED, false).apply();
    }

    /* ---------------- apply ---------------- */

    /**
     * Push the stored configuration back to the car.
     *
     * <p>Order matters. The AC has to be running before the rest will stick,
     * and control mode is set before temperature and fan because switching to
     * auto afterwards would override them. Each write is spaced, since the MCU
     * drops writes that arrive too quickly — the same reason the voice dispatch
     * loop sleeps between commands.
     *
     * @return a human-readable summary of what was applied
     */
    String apply(BydAcApi ac) {
        if (ac == null) return "AC API not available";
        if (!saved)     return "no preset saved yet";

        int applied = 0, failed = 0;

        if (start == 1) { if (ac.start()) applied++; else failed++; settle(); }

        if (controlMode != UNKNOWN) {
            if (ac.setControlMode(controlMode)) applied++; else failed++; settle();
        }
        if (tempDriver != UNKNOWN) {
            if (ac.setTemp(BydAcApi.ZONE_DRIVER, tempDriver)) applied++; else failed++; settle();
        }
        if (tempPassenger != UNKNOWN) {
            if (ac.setTemp(BydAcApi.ZONE_PASSENGER, tempPassenger)) applied++; else failed++; settle();
        }
        if (fan != UNKNOWN) {
            if (ac.setFan(fan)) applied++; else failed++; settle();
        }
        if (windMode != UNKNOWN) {
            if (ac.setWindMode(windMode)) applied++; else failed++; settle();
        }
        if (cycleMode != UNKNOWN) {
            if (ac.setCycleMode(cycleMode)) applied++; else failed++; settle();
        }
        if (compressor != UNKNOWN) {
            if (ac.setCompressor(compressor)) applied++; else failed++; settle();
        }
        // Turning the AC off is done last, so the settings above are stored
        // rather than being written to a system that is shutting down.
        if (start == 0) { if (ac.stop()) applied++; else failed++; }

        String msg = "preset applied (" + applied + " ok"
                   + (failed > 0 ? ", " + failed + " refused" : "") + ")";
        Log.i(TAG, msg + " -> " + describe());
        return msg;
    }

    private static void settle() {
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
    }

    /* ---------------- display ---------------- */

    /** Short human summary, for the UI and the log. */
    String describe() {
        if (!saved) return "nothing saved";
        StringBuilder b = new StringBuilder();
        if (start != UNKNOWN)       b.append(start == 1 ? "AC on" : "AC off");
        if (tempDriver != UNKNOWN)  add(b, tempDriver + "\u00B0");
        if (tempPassenger != UNKNOWN && tempPassenger != tempDriver) {
            add(b, "passenger " + tempPassenger + "\u00B0");
        }
        if (fan != UNKNOWN)         add(b, "fan " + fan);
        if (windMode != UNKNOWN)    add(b, windName(windMode));
        if (cycleMode != UNKNOWN)   add(b, cycleMode == 1 ? "recirculate" : "fresh air");
        return b.length() == 0 ? "nothing saved" : b.toString();
    }

    private static void add(StringBuilder b, String s) {
        if (b.length() > 0) b.append(", ");
        b.append(s);
    }

    private static String windName(int m) {
        switch (m) {
            case BydAcApi.WIND_FACE:         return "face";
            case BydAcApi.WIND_FACE_FOOT:    return "face and feet";
            case BydAcApi.WIND_FOOT:         return "feet";
            case BydAcApi.WIND_FOOT_DEFROST: return "feet and screen";
            case BydAcApi.WIND_DEFROST:      return "screen";
            case BydAcApi.WIND_ALL:          return "everywhere";
            case BydAcApi.WIND_FACE_DEFROST: return "face and screen";
            default:                         return "vent " + m;
        }
    }
}
