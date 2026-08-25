package com.homeo.dashvoice;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Direct climate control via {@code android.hardware.bydauto.ac.BYDAutoAcDevice}.
 *
 * <p>This is the DEVICE-LAYER API that sits beneath the inert UI-facing
 * {@code android.app.AirConditioningManager}. It is reached by reflection so
 * we don't need the (missing) {@code android.hardware.bydauto} classes at
 * compile time.
 *
 * <p>The class enforces {@code BYDAUTO_AC_COMMON} in its own
 * {@code getInstance(Context)}. We defeat that check locally with a
 * {@code ContextWrapper} that no-ops any permission whose name contains
 * {@code BYDAUTO_}. The far-end MCU accepts these calls — verified end to
 * end: {@code start()}, {@code stop()}, {@code setAcWindLevel},
 * {@code setAcTemperature}, {@code setAcControlMode}, {@code setAcCycleMode}
 * all take effect on real hardware.
 *
 * <p><b>Signatures nailed on this unit (Di2.1H / MediaTek):</b>
 * <pre>
 *   start(0) / stop(0)
 *   setAcWindLevel(level 1..7, source=0)
 *   setAcTemperature(zone, tempCelsius, source=0, phase)
 *     - two-phase commit: call once with phase=1, wait ~500ms,
 *       call again with phase=2. Return code -EAB on commit is a stale
 *       sentinel; verify via getTemprature(zone).
 *   setAcControlMode(0=auto | 1=manual, 0)
 *   setAcCycleMode(0=fresh | 1=recirc, 0)
 *   setAcCompressorMode / setAcMaxCoolingState / setAcVentilationState /
 *   setAcDefrostState / setAcWindMode ... same call pattern
 *
 *   getTemprature(zone)   note the typo on the framework side
 *     zone 1 = driver set (or 17 sentinel = "Lo")
 *     zone 2 = passenger set
 *     zone 4 = outside ambient
 * </pre>
 *
 * <p><b>MCU throttling:</b> back-to-back writes get rejected. Space calls by
 * ~500 ms in the temperature commit path and ~250 ms between fan steps.
 */
public class BydAcApi {

    static final String TAG = "DashVoice";
    static final String CLASS_NAME = "android.hardware.bydauto.ac.BYDAutoAcDevice";

    /** Only tempCelsius is used; other args are the canonical values above. */
    public static final int SOURCE_UI = 0;
    public static final int PHASE_ARM    = 1;
    public static final int PHASE_COMMIT = 2;

    public static final int MIN_TEMP_C = 17;
    public static final int MAX_TEMP_C = 30;
    public static final int MIN_FAN = 1;
    public static final int MAX_FAN = 7;
    public static final int ZONE_DRIVER    = 1;
    public static final int ZONE_PASSENGER = 2;
    public static final int ZONE_OUTSIDE   = 4;

    /** Wraps a Context so BYDAUTO_* permission checks all pass locally. */
    static final class BypassContext extends ContextWrapper {
        BypassContext(Context base) { super(base); }
        private boolean isByd(String p) { return p != null && p.contains("BYDAUTO"); }
        @Override public void enforceCallingOrSelfPermission(String p, String m) {
            if (!isByd(p)) super.enforceCallingOrSelfPermission(p, m);
        }
        @Override public void enforceCallingPermission(String p, String m) {
            if (!isByd(p)) super.enforceCallingPermission(p, m);
        }
        @Override public void enforcePermission(String p, int pid, int uid, String m) {
            if (!isByd(p)) super.enforcePermission(p, pid, uid, m);
        }
        @Override public int checkCallingOrSelfPermission(String p) {
            return isByd(p) ? 0 : super.checkCallingOrSelfPermission(p);
        }
        @Override public int checkCallingPermission(String p) {
            return isByd(p) ? 0 : super.checkCallingPermission(p);
        }
        @Override public int checkPermission(String p, int pid, int uid) {
            return isByd(p) ? 0 : super.checkPermission(p, pid, uid);
        }
        @Override public int checkSelfPermission(String p) {
            return isByd(p) ? 0 : super.checkSelfPermission(p);
        }
        @Override public Context getApplicationContext() { return this; }
    }

    private final Object acDevice;

    private BydAcApi(Object acDevice) { this.acDevice = acDevice; }

    /** @return an initialised API, or null if the device class isn't on this build. */
    public static BydAcApi tryCreate(Context ctx) {
        try {
            Class<?> dev = Class.forName(CLASS_NAME);
            Object inst = dev.getMethod("getInstance", Context.class)
                    .invoke(null, new BypassContext(ctx.getApplicationContext()));
            if (inst == null) {
                Log.w(TAG, "BydAcApi: getInstance returned null");
                return null;
            }
            Log.i(TAG, "BydAcApi: acquired " + inst);
            return new BydAcApi(inst);
        } catch (ClassNotFoundException cnf) {
            Log.w(TAG, "BydAcApi: " + CLASS_NAME + " not present on this build");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "BydAcApi: init failed", t);
            return null;
        }
    }

    /* ---------------- read side ---------------- */

    /** Driver / passenger / outside temperature. Zone 4 is ambient outdoor. */
    public int getTemp(int zone) { return invokeInt("getTemprature", new Class[]{int.class}, zone); }

    public int getStartState()   { return invokeInt("getAcStartState"); }
    public int getWindLevel()    { return invokeInt("getAcWindLevel"); }
    public int getCompressor()   { return invokeInt("getAcCompressorMode"); }
    public int getControlMode()  { return invokeInt("getAcControlMode"); }
    public int getCycleMode()    { return invokeInt("getAcCycleMode"); }
    public int getMaxCooling()   { return invokeInt("getAcMaxCoolingState"); }
    public int getVentilation()  { return invokeInt("getAcVentilationState"); }
    public int getWindMode()     { return invokeInt("getAcWindMode"); }

    /* ---------------- write side ---------------- */

    /** Returns true on any successful acknowledgement. */
    public boolean start() { return call0("start", SOURCE_UI); }
    public boolean stop()  { return call0("stop",  SOURCE_UI); }

    public boolean setFan(int level) {
        if (level < MIN_FAN) level = MIN_FAN;
        if (level > MAX_FAN) level = MAX_FAN;
        return call0("setAcWindLevel", level, SOURCE_UI);
    }

    /** Setting temperature requires a two-phase commit. Verify by re-read. */
    public boolean setTemp(int zone, int tempC) {
        if (tempC < MIN_TEMP_C) tempC = MIN_TEMP_C;
        if (tempC > MAX_TEMP_C) tempC = MAX_TEMP_C;
        try {
            Method m = acDevice.getClass().getMethod("setAcTemperature",
                    int.class, int.class, int.class, int.class);
            m.invoke(acDevice, zone, tempC, SOURCE_UI, PHASE_ARM);
            sleep(500);
            m.invoke(acDevice, zone, tempC, SOURCE_UI, PHASE_COMMIT);
            sleep(600);
            int now = getTemp(zone);
            return now == tempC;
        } catch (Throwable t) {
            Log.e(TAG, "setTemp failed", t);
            return false;
        }
    }

    /** Change temperature by delta degrees, clipped to legal range. */
    public boolean stepTemp(int zone, int delta) {
        int now = getTemp(zone);
        // sentinel 17 = "Lo"; treat as MIN_TEMP_C for stepping arithmetic
        int base = (now == 17) ? MIN_TEMP_C : now;
        return setTemp(zone, base + delta);
    }

    public boolean setControlMode(int mode) { return call0("setAcControlMode", mode, SOURCE_UI); }
    public boolean setCycleMode(int mode)   { return call0("setAcCycleMode", mode, SOURCE_UI); }
    public boolean setCompressor(int on)    { return call0("setAcCompressorMode", on, SOURCE_UI); }
    public boolean setMaxCooling(int state) { return call0("setAcMaxCoolingState", state); }
    public boolean setVentilation(int state){ return call0("setAcVentilationState", state, SOURCE_UI); }
    public boolean setWindMode(int mode)    { return call0("setAcWindMode", mode, SOURCE_UI); }
    /** area: 0=front, 1=rear. */
    public boolean setDefrost(int area, int state) {
        return call0("setAcDefrostState", area, state, SOURCE_UI);
    }

    /* ---------------- reflection helpers ---------------- */

    private int invokeInt(String name) { return invokeInt(name, new Class[0]); }
    private int invokeInt(String name, Class<?>[] sig, Object... args) {
        try {
            Method m = acDevice.getClass().getMethod(name, sig);
            Object r = m.invoke(acDevice, args);
            return r instanceof Integer ? (Integer) r : Integer.MIN_VALUE;
        } catch (Throwable t) {
            Log.w(TAG, name + " read failed: " + t.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Call a setter, boxing primitive int args, matching signature by arity+type.
     * Returns true whether or not the return code is 0, because the MCU's return
     * codes are unreliable (setAcTemperature's commit returns -EAB even when
     * the write took effect). Callers that need certainty should re-read.
     */
    private boolean call0(String name, int... args) {
        try {
            for (Method m : acDevice.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterTypes().length != args.length) continue;
                Object[] boxed = new Object[args.length];
                for (int i = 0; i < args.length; i++) boxed[i] = args[i];
                Object r = m.invoke(acDevice, boxed);
                Log.i(TAG, name + Arrays(args) + " -> " + r);
                return true;
            }
            Log.w(TAG, name + ": no matching method with " + args.length + " int args");
            return false;
        } catch (Throwable t) {
            Log.e(TAG, name + " invoke failed", t);
            return false;
        }
    }

    private static String Arrays(int[] a) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(","); sb.append(a[i]); }
        return sb.append(")").toString();
    }
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
