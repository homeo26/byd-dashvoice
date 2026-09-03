// Dumps the whole android.hardware.bydauto.ac.BYDAutoAcDevice method surface,
// and reads every no-argument getter, so the control behind a UI button can be
// identified by name and by watching which value changes when it is pressed.
//
// Read-only: no setters are called.
//
// Build and run:
//   SDK=~/Library/Android/sdk
//   javac -source 8 -target 8 -bootclasspath $SDK/platforms/android-36/android.jar \
//         -d /tmp/acdump tools/AcMethodDump.java
//   $SDK/build-tools/36.0.0/d8 --min-api 28 --output /tmp/acdump /tmp/acdump/*.class
//   adb push /tmp/acdump/classes.dex /data/local/tmp/acdump.dex
//   adb shell 'setsid sh -c "CLASSPATH=/data/local/tmp/acdump.dex timeout 30 \
//     app_process / AcMethodDump > /data/local/tmp/acdump.out 2>&1" >/dev/null 2>&1 </dev/null &'
//   sleep 12; adb shell cat /data/local/tmp/acdump.out
//
// Usage for identifying a button: run once, press the button on the car's own
// AC screen, run again, and diff the two outputs.

import android.content.Context;
import android.content.ContextWrapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AcMethodDump {

    /** Same permission bypass the app uses; needed even for getInstance. */
    static class Ctx extends ContextWrapper {
        Ctx(Context b) { super(b); }
        boolean byd(String p) { return p != null && p.contains("BYDAUTO"); }
        public void enforceCallingOrSelfPermission(String p, String m) {
            if (!byd(p)) super.enforceCallingOrSelfPermission(p, m); }
        public void enforceCallingPermission(String p, String m) {
            if (!byd(p)) super.enforceCallingPermission(p, m); }
        public void enforcePermission(String p, int pid, int uid, String m) {
            if (!byd(p)) super.enforcePermission(p, pid, uid, m); }
        public int checkCallingOrSelfPermission(String p) {
            return byd(p) ? 0 : super.checkCallingOrSelfPermission(p); }
        public int checkCallingPermission(String p) {
            return byd(p) ? 0 : super.checkCallingPermission(p); }
        public int checkPermission(String p, int pid, int uid) {
            return byd(p) ? 0 : super.checkPermission(p, pid, uid); }
        public int checkSelfPermission(String p) {
            return byd(p) ? 0 : super.checkSelfPermission(p); }
        public Context getApplicationContext() { return this; }
    }

    /** Names worth flagging when hunting for a specific control. */
    private static final String[] INTERESTING = {
        "dual", "zone", "sync", "blow", "outlet", "vent", "wind", "mode",
        "seat", "rear", "auto", "level", "position", "direction", "swing"
    };

    public static void main(String[] a) throws Exception {
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object sys = at.getMethod("getSystemContext")
                       .invoke(at.getMethod("systemMain").invoke(null));
        Context ctx = new Ctx((Context) sys);

        Class<?> dev = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice");
        Object ac = dev.getMethod("getInstance", Context.class).invoke(null, ctx);
        System.out.println("device = " + ac);
        System.out.println();

        // ---- full method surface ----
        List<String> sigs = new ArrayList<String>();
        for (Method m : dev.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            StringBuilder b = new StringBuilder();
            b.append(m.getReturnType().getSimpleName()).append(' ')
             .append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) b.append(", ");
                b.append(ps[i].getSimpleName());
            }
            b.append(')');
            sigs.add(b.toString());
        }
        Collections.sort(sigs);

        System.out.println("=== ALL METHODS (" + sigs.size() + ") ===");
        for (String s : sigs) System.out.println("  " + s);

        System.out.println();
        System.out.println("=== CANDIDATES for a vent / dual-zone control ===");
        for (String s : sigs) {
            String low = s.toLowerCase();
            for (String k : INTERESTING) {
                if (low.contains(k)) { System.out.println("  " + s); break; }
            }
        }

        // ---- live values of every no-arg getter ----
        System.out.println();
        System.out.println("=== NO-ARG GETTER VALUES (diff these across a button press) ===");
        for (Method m : dev.getMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (!m.getName().startsWith("get") && !m.getName().startsWith("is")) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Object v;
            try { v = m.invoke(ac); }
            catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                v = "ERR " + c.getClass().getSimpleName();
            }
            System.out.println("  " + m.getName() + " = " + v);
        }

        // ---- zone-indexed getters ----
        System.out.println();
        System.out.println("=== getTemprature(zone) 0..6 ===");
        try {
            Method t = dev.getMethod("getTemprature", int.class);
            for (int z = 0; z <= 6; z++) {
                Object v;
                try { v = t.invoke(ac, z); } catch (Throwable e) { v = "ERR"; }
                System.out.println("  zone " + z + " = " + v);
            }
        } catch (Throwable t) {
            System.out.println("  getTemprature(int) not present");
        }

        System.exit(0);
    }
}
