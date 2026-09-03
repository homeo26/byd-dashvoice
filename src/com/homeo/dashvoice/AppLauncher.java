package com.homeo.dashvoice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Launches installed apps by voice — "open music", "open maps", "open radio".
 *
 * <p><b>Why app labels are not used directly.</b> Recognition runs against a
 * constrained grammar, and every word in that grammar has to exist in the
 * acoustic model's vocabulary. Most apps on this head unit are labelled in
 * Chinese, and brand names like "Waze" are unlikely to be in a small US
 * English model. Feeding those into the grammar risks breaking recognition for
 * every other command, not just the app ones.
 *
 * <p>So the primary mechanism is a curated table of <em>plain English words</em>
 * mapped to candidate packages. "music", "maps", "settings", "radio", "phone"
 * are ordinary vocabulary words and safe to put in a grammar. Each entry lists
 * several candidate packages and the first one actually installed wins, which
 * keeps it working across firmware variants without hardcoding one build.
 *
 * <p>Auto-discovery is a secondary source: any launchable app whose label is
 * pure ASCII letters is offered too. Labels with non-ASCII characters are
 * skipped precisely because they cannot be spoken to an English model.
 *
 * <p>{@link #phrases()} feeds the grammar. If the vocabulary rejects anything,
 * {@link VoskEngine} falls back to the static grammar so a bad app name can
 * never take the whole recogniser down.
 */
final class AppLauncher {

    private static final String TAG = "DashVoice";

    /**
     * Spoken word -> candidate packages, most specific first.
     *
     * <p>Package names verified against a package dump from the target unit.
     * Several obvious guesses were wrong and are corrected here: this firmware
     * has no {@code com.byd.radio}, no AMap or Baidu navigation, and names its
     * telephony, camera and Bluetooth apps {@code bluetoothcall},
     * {@code bydcamera} and {@code btsetting} rather than the more predictable
     * forms. Multiple candidates are kept anyway so the table still works on
     * other BYD builds.
     */
    private static final String[][] ALIASES = {
        // media
        { "music",       "com.byd.mediacenter", "com.byd.musicwidget", "com.android.music" },
        { "media",       "com.byd.mediacenter" },
        { "radio",       "com.byd.radio", "com.byd.mediacenter" },
        { "video",       "com.byd.mediapreview", "com.byd.mediacenter" },
        { "gallery",     "com.byd.mediapreview", "com.android.gallery3d" },
        // navigation - no AMap or Baidu on this build, but Google Maps is present
        { "maps",        "com.google.android.apps.maps", "com.autonavi.amapauto", "com.baidu.navi" },
        { "navigation",  "com.google.android.apps.maps", "com.autonavi.amapauto", "com.baidu.navi" },
        // telephony and bluetooth
        { "phone",       "com.byd.bluetoothcall", "com.byd.xcall", "com.android.dialer" },
        { "calls",       "com.byd.bluetoothcall" },
        { "bluetooth",   "com.byd.btsetting", "com.byd.bluetoothcall" },
        // settings
        { "settings",    "com.byd.systemsettings", "com.android.settings" },
        { "car settings","com.byd.carsettings" },
        // climate, so the AC screen can be reached when a command is refused
        { "climate",     "com.byd.airconditioning" },
        { "air",         "com.byd.airconditioning" },
        // misc
        { "camera",      "com.byd.bydcamera", "com.mediatek.emcamera", "com.android.camera2" },
        { "browser",     "com.android.browser", "com.xbrowser.play", "com.android.chrome" },
        { "store",       "com.byd.appstore", "com.aurora.store", "com.android.vending" },
        { "launcher",    "com.homeo.splitlauncher", "com.android.launcher3" },
        // installed by this project; absent from the reference dump because it
        // predates them, and resolved at runtime if present
        { "youtube",     "com.homeo.wheeltube" },
        { "split",       "com.homeo.splitlauncher" },
    };

    /** Resolved spoken word -> package, built at refresh time. */
    private final Map<String, String> resolved = new LinkedHashMap<String, String>();
    /** Spoken word -> human label, for the UI. */
    private final Map<String, String> labels = new LinkedHashMap<String, String>();

    private AppLauncher() {}

    /* ---------------- construction ---------------- */

    /**
     * Build the table for what is actually installed. Cheap enough to call at
     * startup; queries the launcher intent once.
     */
    static AppLauncher build(Context ctx) {
        AppLauncher a = new AppLauncher();
        if (ctx == null) return a;
        PackageManager pm = ctx.getPackageManager();

        // 1. curated aliases, first installed candidate wins
        for (String[] row : ALIASES) {
            String word = row[0];
            for (int i = 1; i < row.length; i++) {
                String pkg = row[i];
                if (pm.getLaunchIntentForPackage(pkg) != null) {
                    a.resolved.put(word, pkg);
                    a.labels.put(word, labelOf(pm, pkg, pkg));
                    break;
                }
            }
        }

        // 2. auto-discovery, but only labels that can actually be spoken
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                CharSequence lab = ri.loadLabel(pm);
                if (lab == null) continue;
                String spoken = speakable(lab.toString());
                if (spoken == null) continue;                   // not sayable
                if (a.resolved.containsKey(spoken)) continue;   // alias wins
                a.resolved.put(spoken, pkg);
                a.labels.put(spoken, lab.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "AppLauncher: enumeration failed", t);
        }

        Log.i(TAG, "AppLauncher: " + a.resolved.size() + " launchable names");
        return a;
    }

    private static String labelOf(PackageManager pm, String pkg, String fallback) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? fallback : l.toString();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Reduce a label to something an English model can plausibly recognise, or
     * null if it cannot. Requires pure ASCII letters and spaces, which excludes
     * the Chinese-labelled system apps by design, and caps the length so a
     * sentence-like label does not bloat the grammar.
     */
    private static String speakable(String label) {
        String s = label.trim().toLowerCase();
        if (s.isEmpty() || s.length() > 24) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') b.append(c);
            else if (c == ' ' || c == '-' || c == '_') b.append(' ');
            else return null;             // digits, punctuation, non-ASCII
        }
        String out = b.toString().replaceAll("\\s+", " ").trim();
        if (out.length() < 3) return null;
        // Two words maximum keeps the phrase short enough to say naturally.
        String[] parts = out.split(" ");
        if (parts.length > 2) out = parts[0] + " " + parts[1];
        return out;
    }

    /* ---------------- grammar and matching ---------------- */

    /** Phrases to add to the recogniser grammar. */
    List<String> phrases() {
        List<String> out = new ArrayList<String>();
        for (String word : resolved.keySet()) {
            out.add("open " + word);
        }
        Collections.sort(out);
        return out;
    }

    /** Names available, for the on-screen reference. */
    List<String> names() {
        List<String> out = new ArrayList<String>(resolved.keySet());
        Collections.sort(out);
        return out;
    }

    String labelFor(String word) {
        String l = labels.get(word);
        return l == null ? word : l;
    }

    boolean isEmpty() { return resolved.isEmpty(); }

    /**
     * If the text contains "open &lt;name&gt;", return that name. Longest match
     * wins so "open media center" is preferred over "open media".
     */
    String findName(String recognised) {
        if (recognised == null) return null;
        String t = recognised.toLowerCase();
        String best = null;
        for (String word : resolved.keySet()) {
            if (t.contains("open " + word)) {
                if (best == null || word.length() > best.length()) best = word;
            }
        }
        return best;
    }

    /* ---------------- launching ---------------- */

    /**
     * Start the app mapped to a spoken name.
     *
     * <p>Called from a foreground service as well as the activity. Background
     * activity starts are permitted on this platform version, which is why this
     * works without the app being on screen; it would need rethinking on
     * Android 10 or newer.
     */
    Commands.Result launch(Context ctx, String word) {
        String pkg = resolved.get(word);
        if (pkg == null) return Commands.Result.fail("don't know how to open " + word);
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return Commands.Result.fail(labelFor(word) + " is not installed");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                     | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return Commands.Result.ok("opening " + labelFor(word));
        } catch (Throwable t) {
            Log.w(TAG, "AppLauncher: launch failed for " + pkg, t);
            return Commands.Result.fail("could not open " + labelFor(word));
        }
    }
}
