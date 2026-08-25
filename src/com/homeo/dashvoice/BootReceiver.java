package com.homeo.dashvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Starts the {@link MicKeyService} at boot, if the user has enabled the
 * steering-wheel mic hook. This means the mic button on the wheel triggers
 * DashVoice from ignition, without the user having to launch the app.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DashVoice";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences p = ctx.getSharedPreferences(MicKeyService.PREFS, Context.MODE_PRIVATE);
        boolean enabled = p.getBoolean(MicKeyService.KEY_MIC_HOOK, false);
        Log.i(TAG, "BootReceiver: " + intent.getAction() + " (hook=" + enabled + ")");
        if (enabled) MicKeyService.ensureRunning(ctx);
    }
}
