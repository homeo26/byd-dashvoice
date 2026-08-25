package com.homeo.dashvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Brings {@link MicKeyService} up without the user opening the app, so the
 * steering-wheel mic button works from ignition.
 *
 * <p>Listens for more than {@code BOOT_COMPLETED} on purpose:
 * <ul>
 *   <li>{@code LOCKED_BOOT_COMPLETED} fires earlier, before user unlock.</li>
 *   <li>{@code QUICKBOOT_POWERON} is what several Chinese ROMs send instead
 *       of the standard boot broadcast.</li>
 *   <li>{@code MY_PACKAGE_REPLACED} re-arms after an update, since replacing
 *       the APK kills the running service.</li>
 *   <li>{@code USER_PRESENT} is a late fallback: if every boot broadcast was
 *       missed, the first unlock still starts things.</li>
 * </ul>
 *
 * <p><b>Limitation worth knowing.</b> None of this can fire while the package
 * is in Android's stopped state, which {@code am force-stop} sets and which
 * suppresses every broadcast to the app. Only launching the app by hand
 * clears it. That is exactly what happened during development: repeated
 * force-stops left {@code stopped=true}, so after a reboot the service never
 * came back and it looked as though autostart was broken.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DashVoice";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "(null)" : intent.getAction();
        SharedPreferences p =
                ctx.getSharedPreferences(MicKeyService.PREFS, Context.MODE_PRIVATE);
        boolean enabled = p.getBoolean(MicKeyService.KEY_MIC_HOOK,
                                       MicKeyService.HOOK_DEFAULT);
        Log.i(TAG, "BootReceiver: " + action + " (hook=" + enabled + ")");
        if (!enabled) return;
        try {
            MicKeyService.ensureRunning(ctx);
        } catch (Throwable t) {
            // Starting a foreground service from a boot broadcast can be
            // refused on some builds; log rather than crash the receiver.
            Log.w(TAG, "BootReceiver: could not start service", t);
        }
    }
}
