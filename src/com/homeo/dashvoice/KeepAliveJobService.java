package com.homeo.dashvoice;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Starts {@link MicKeyService} after boot, and periodically re-checks that it
 * is still alive.
 *
 * <p><b>Why this exists rather than relying on {@link BootReceiver}.</b> This
 * firmware patches the framework's broadcast dispatch with a vendor
 * self-start filter that drops boot broadcasts to third-party apps. It is
 * visible in logcat at every boot:
 *
 * <pre>
 * BroadcastQueue: ssc_skip reciever for uid 10073 name = com.homeo.dashvoice
 * BroadcastQueue: ssc_skip reciever for uid 10073 name = com.homeo.dashvoice ignored !!!
 * </pre>
 *
 * System packages are unaffected — the stock assistant's {@code PowerOnReceiver}
 * receives {@code BOOT_COMPLETED} normally — but our receiver never runs, so
 * the service stayed dead after every reboot until the app was opened by hand.
 *
 * <p>A persisted {@link JobScheduler} job is re-registered by the system
 * itself across reboots and dispatched through job scheduling rather than
 * broadcast delivery, so the self-start filter does not apply. The periodic
 * interval also makes it self-healing if the service is killed for memory.
 *
 * <p>{@link JobInfo.Builder#setPersisted} requires
 * {@code RECEIVE_BOOT_COMPLETED}, which the manifest already declares.
 */
public class KeepAliveJobService extends JobService {

    private static final String TAG = "DashVoice";
    private static final int JOB_ID = 4711;

    /**
     * How long after one run the next is queued. Chained one-shot jobs are
     * used rather than {@link JobInfo.Builder#setPeriodic}, because a periodic
     * job computes its next run from the previous one: after a reboot it sat
     * "earliest=+12m30s" away, so the service took a quarter of an hour to
     * come up. A one-shot job whose window has already elapsed is runnable the
     * moment the scheduler starts, which puts the service up within seconds of
     * boot instead.
     */
    private static final long CHAIN_DELAY_MS = 5 * 60 * 1000L;
    /** Latency for the next link in the chain. */
    private static final long MIN_LATENCY_MS = CHAIN_DELAY_MS;
    /** Deadline after which the scheduler should run it regardless. */
    private static final long DEADLINE_MS = CHAIN_DELAY_MS * 2;

    @Override
    public boolean onStartJob(JobParameters params) {
        SharedPreferences p =
                getSharedPreferences(MicKeyService.PREFS, Context.MODE_PRIVATE);
        boolean enabled = p.getBoolean(MicKeyService.KEY_MIC_HOOK,
                                       MicKeyService.HOOK_DEFAULT);
        Log.i(TAG, "KeepAliveJob: fired (hook=" + enabled + ")");
        if (enabled) {
            try {
                MicKeyService.ensureRunning(this);
            } catch (Throwable t) {
                Log.w(TAG, "KeepAliveJob: could not start service", t);
            }
            // Queue the next link, so the chain keeps the service alive and a
            // pending job always exists for the next boot to pick up.
            schedule(this);
        }
        return false;   // nothing async; work is done
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;    // reschedule if the system cut us short
    }

    /** Queue the next one-shot persisted job. Idempotent. */
    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, KeepAliveJobService.class))
                    .setPersisted(true)                       // survives reboot
                    .setMinimumLatency(MIN_LATENCY_MS)
                    .setOverrideDeadline(DEADLINE_MS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setRequiresBatteryNotLow(false).setRequiresStorageNotLow(false);
            }
            int r = js.schedule(b.build());
            Log.i(TAG, "KeepAliveJob: scheduled result=" + r
                    + " (1=success, 0=failure)");
        } catch (Throwable t) {
            Log.w(TAG, "KeepAliveJob: schedule failed", t);
        }
    }

    public static void cancel(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignored) {}
    }
}
