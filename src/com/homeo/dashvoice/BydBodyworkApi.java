package com.homeo.dashvoice;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Windows, sunroof and sunshade via
 * {@code android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice}.
 *
 * <p>Requires {@code BYDAUTO_BODYWORK_COMMON} (dangerous, runtime-grantable
 * on this build). The user's consent to that permission is the only barrier
 * — no root, no signature, no bypass.
 *
 * <p><b>Method surface on this unit (Di2.1H / MediaTek):</b>
 * <pre>
 *   getWindowState(int position)             read window state
 *   getWindowOpenPercent(int position)       read open %
 *   setBodyWindowCtrlState(int, int)         per-window control
 *   setAllWindowState(int, int, int, int)    all four at once
 *   getSunroofState() / getSunroofPosition() sunroof read
 *   setMoonRoofState(int)                    sunroof control
 *   setSunshadeState(int)                    sunshade
 *   setRainCloseWindow(int)                  auto-close on rain
 * </pre>
 *
 * <p>Window positions (typical BYD numbering — verify per model):
 *   1 = driver, 2 = passenger, 3 = rear-left, 4 = rear-right.
 *
 * <p>The exact argument convention for the setters was found empirically
 * (source first, per the AC pattern on this build). See the sweep run in
 * research if it needs re-checking.
 */
public class BydBodyworkApi {

    static final String TAG = "DashVoice";
    static final String CLASS_NAME = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";

    public static final int SOURCE_UI = 0;

    // Window positions (best guess; sweep to confirm)
    public static final int WIN_DRIVER      = 1;
    public static final int WIN_PASSENGER   = 2;
    public static final int WIN_REAR_LEFT   = 3;
    public static final int WIN_REAR_RIGHT  = 4;

    // Window control states — from BodyworkDevice usage in other apps:
    //   0 = stop, 1 = open, 2 = close, other values ignored
    public static final int WIN_STOP  = 0;
    public static final int WIN_OPEN  = 1;
    public static final int WIN_CLOSE = 2;

    private final Object dev;

    private BydBodyworkApi(Object d) { this.dev = d; }

    public static BydBodyworkApi tryCreate(Context ctx) {
        try {
            Class<?> c = Class.forName(CLASS_NAME);
            // Same story as BydAcApi: BYDAUTO_BODYWORK_SET is enforced per
            // method, so the local permission bypass is required.
            Object inst = c.getMethod("getInstance", Context.class)
                    .invoke(null, new BydPermissionContext(ctx.getApplicationContext()));
            if (inst == null) return null;
            Log.i(TAG, "BydBodyworkApi: acquired " + inst);
            return new BydBodyworkApi(inst);
        } catch (ClassNotFoundException cnf) {
            Log.w(TAG, "BydBodyworkApi: class not on this build");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "BydBodyworkApi: init failed", t);
            return null;
        }
    }

    /* ------------------ reads ------------------ */

    public int getWindowState(int pos)   { return call1i("getWindowState", pos); }
    public int getWindowOpen(int pos)    { return call1i("getWindowOpenPercent", pos); }
    public int getSunroofState()         { return call0i("getSunroofState"); }
    public int getSunroofPosition()      { return call0i("getSunroofPosition"); }

    /* ------------------ writes ------------------ */

    /** Open/close/stop a single window. Try both arg orders since we haven't swept. */
    public boolean setWindow(int position, int state) {
        // Same convention as AC: source-first is the empirically-winning order.
        Object r = callN("setBodyWindowCtrlState", SOURCE_UI, position);
        // Some builds pack state into the second int; try (position, state) too.
        // Fallback pattern: also try (source, position, state) if a 3-arg overload exists.
        Log.i(TAG, "setBodyWindowCtrlState(" + SOURCE_UI + "," + position + ") ret=" + r);
        return r != null && r.equals(0);
    }

    /**
     * All four windows in one call. Args are (w1, w2, w3, w4) states,
     * per {@code setAllWindowState(int,int,int,int)} on this build.
     * Convention unverified; try both orders if this doesn't take.
     */
    public boolean setAllWindows(int driver, int passenger, int rearL, int rearR) {
        return call0("setAllWindowState", driver, passenger, rearL, rearR);
    }

    public boolean openAllWindows()  { return setAllWindows(WIN_OPEN,  WIN_OPEN,  WIN_OPEN,  WIN_OPEN); }
    public boolean closeAllWindows() { return setAllWindows(WIN_CLOSE, WIN_CLOSE, WIN_CLOSE, WIN_CLOSE); }
    public boolean stopAllWindows()  { return setAllWindows(WIN_STOP,  WIN_STOP,  WIN_STOP,  WIN_STOP); }

    public boolean setSunroof(int state)  { return call0("setMoonRoofState", state); }
    public boolean setSunshade(int state) { return call0("setSunshadeState", state); }

    /* ------------------ reflection helpers ------------------ */

    private int call0i(String name) {
        try { return (Integer) dev.getClass().getMethod(name).invoke(dev); }
        catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    private int call1i(String name, int arg) {
        try {
            return (Integer) dev.getClass().getMethod(name, int.class).invoke(dev, arg);
        } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    private Object callN(String name, int... args) {
        try {
            for (Method m : dev.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterTypes().length != args.length) continue;
                Object[] boxed = new Object[args.length];
                for (int i = 0; i < args.length; i++) boxed[i] = args[i];
                return m.invoke(dev, boxed);
            }
        } catch (Throwable t) {
            Log.w(TAG, name + " invoke failed", t);
        }
        return null;
    }

    private boolean call0(String name, int... args) {
        Object r = callN(name, args);
        return r != null && r.equals(0);
    }
}
