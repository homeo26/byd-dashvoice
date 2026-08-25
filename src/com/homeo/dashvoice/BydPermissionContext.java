package com.homeo.dashvoice;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * Shared context wrapper that no-ops every BYDAUTO_* permission check.
 *
 * <p>Necessary because although {@code BYDAUTO_*_COMMON} permissions are
 * dangerous-level and can be granted at runtime, the individual
 * {@code BYDAUTO_*_SET} / {@code _GET} permissions each device method
 * enforces at call time are {@code signature|privileged} and cannot be
 * granted to a normal app.
 *
 * <p>The framework checks these via
 * {@link Context#enforceCallingOrSelfPermission} (and its checkers), all of
 * which we intercept. Empirically the far-end MCU accepts the calls once the
 * local enforce checks pass — the framework's enforcement is the only real
 * gate for a non-system app.
 *
 * <p>We only intercept names containing {@code BYDAUTO_} so unrelated
 * permission checks flow through unchanged.
 */
final class BydPermissionContext extends ContextWrapper {
    private static final int GRANTED = 0; // PackageManager.PERMISSION_GRANTED

    BydPermissionContext(Context base) { super(base); }

    private static boolean isByd(String p) { return p != null && p.contains("BYDAUTO"); }

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
        return isByd(p) ? GRANTED : super.checkCallingOrSelfPermission(p);
    }
    @Override public int checkCallingPermission(String p) {
        return isByd(p) ? GRANTED : super.checkCallingPermission(p);
    }
    @Override public int checkPermission(String p, int pid, int uid) {
        return isByd(p) ? GRANTED : super.checkPermission(p, pid, uid);
    }
    @Override public int checkSelfPermission(String p) {
        return isByd(p) ? GRANTED : super.checkSelfPermission(p);
    }
    @Override public Context getApplicationContext() { return this; }
}
