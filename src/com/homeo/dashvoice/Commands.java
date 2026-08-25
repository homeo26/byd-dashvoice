package com.homeo.dashvoice;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * The command vocabulary.
 *
 * Two jobs:
 *   1. Produce the JSON word list handed to Vosk as a constrained grammar.
 *      Constraining the decoder to these phrases is what lifts recognition
 *      from "fun up" (free-form, conf 0.51) to "fan up" (conf 1.00) on real
 *      cabin audio with the AC running.
 *   2. Map a recognised phrase to a concrete action on the climate UI.
 *
 * Deliberately data-driven rather than lambda-based: this module is compiled
 * against android.jar, which has no java.lang.invoke.LambdaMetafactory.
 */
public final class Commands {

    // operations
    static final int OP_ENSURE_ON  = 0;   // tap only if not already on
    static final int OP_ENSURE_OFF = 1;   // tap only if not already off
    static final int OP_TAP        = 2;   // single tap
    static final int OP_TAP_N      = 3;   // repeat tap (arg = count)
    static final int OP_FAN_LEVEL  = 4;   // positional tap on the fan track

    /** phrase, op, target resource id, repeat count */
    static final class Cmd {
        final String phrase; final int op; final String viewId; final int arg;
        Cmd(String phrase, int op, String viewId, int arg) {
            this.phrase = phrase; this.op = op; this.viewId = viewId; this.arg = arg;
        }
    }

    private static final Cmd[] CMDS = {
        // ---- AC power ----
        new Cmd("air conditioning on",  OP_ENSURE_ON,  ClimateService.ID_AC_POWER, 0),
        new Cmd("air conditioning off", OP_ENSURE_OFF, ClimateService.ID_AC_POWER, 0),
        new Cmd("ac on",                OP_ENSURE_ON,  ClimateService.ID_AC_POWER, 0),
        new Cmd("ac off",               OP_ENSURE_OFF, ClimateService.ID_AC_POWER, 0),

        // ---- Compressor ----
        new Cmd("compressor on",  OP_ENSURE_ON,  ClimateService.ID_COMPRESSOR, 0),
        new Cmd("compressor off", OP_ENSURE_OFF, ClimateService.ID_COMPRESSOR, 0),

        // ---- Modes ----
        new Cmd("auto mode",   OP_ENSURE_ON, ClimateService.ID_AUTO_MODE, 0),
        new Cmd("max cooling", OP_ENSURE_ON, ClimateService.ID_MAX_COOLING, 0),

        // ---- Defrost ----
        new Cmd("front defrost", OP_ENSURE_ON,  ClimateService.ID_FRONT_DEFROST, 0),
        new Cmd("rear defrost",  OP_ENSURE_ON,  ClimateService.ID_REAR_DEFROST, 0),
        new Cmd("defrost off",   OP_ENSURE_OFF, ClimateService.ID_FRONT_DEFROST, 0),

        // ---- Air source ----
        new Cmd("recirculate", OP_ENSURE_ON,  ClimateService.ID_RECIRCULATE, 0),
        new Cmd("fresh air",   OP_ENSURE_OFF, ClimateService.ID_RECIRCULATE, 0),

        // ---- Fan ----
        // wind_min_id / wind_max_id are endpoint buttons, not steps, so the
        // phrases name them honestly. Intermediate levels use the track.
        new Cmd("fan maximum", OP_TAP,   ClimateService.ID_FAN_MAX, 0),
        new Cmd("fan minimum", OP_TAP,   ClimateService.ID_FAN_MIN, 0),
        new Cmd("fan full",    OP_TAP,   ClimateService.ID_FAN_MAX, 0),
        new Cmd("fan one",     OP_FAN_LEVEL, null, 1),
        new Cmd("fan two",     OP_FAN_LEVEL, null, 2),
        new Cmd("fan three",   OP_FAN_LEVEL, null, 3),
        new Cmd("fan four",    OP_FAN_LEVEL, null, 4),
        new Cmd("fan five",    OP_FAN_LEVEL, null, 5),
        new Cmd("fan six",     OP_FAN_LEVEL, null, 6),
        new Cmd("fan seven",   OP_FAN_LEVEL, null, 7),

        // ---- Temperature (driver) ----
        new Cmd("temperature up",   OP_TAP,   ClimateService.ID_TEMP_UP,   0),
        new Cmd("temperature down", OP_TAP,   ClimateService.ID_TEMP_DOWN, 0),
        new Cmd("warmer",           OP_TAP,   ClimateService.ID_TEMP_UP,   0),
        new Cmd("colder",           OP_TAP,   ClimateService.ID_TEMP_DOWN, 0),
        new Cmd("much warmer",      OP_TAP_N, ClimateService.ID_TEMP_UP,   3),
        new Cmd("much colder",      OP_TAP_N, ClimateService.ID_TEMP_DOWN, 3),

        // ---- Temperature (passenger) ----
        new Cmd("passenger warmer", OP_TAP, ClimateService.ID_PASS_TEMP_UP, 0),
        new Cmd("passenger colder", OP_TAP, ClimateService.ID_PASS_TEMP_DN, 0),

        // ---- Vent direction ----
        new Cmd("vent face",      OP_ENSURE_ON, ClimateService.ID_VENT_FACE,   0),
        new Cmd("vent feet",      OP_ENSURE_ON, ClimateService.ID_VENT_FOOT,   0),
        new Cmd("ventilation on", OP_ENSURE_ON, ClimateService.ID_VENTILATION, 0),
    };

    /** Phrases in registration order. */
    public static List<String> phrases() {
        List<String> out = new ArrayList<>(CMDS.length);
        for (Cmd c : CMDS) out.add(c.phrase);
        return out;
    }

    /**
     * JSON array Vosk expects for a constrained grammar. "[unk]" lets the
     * decoder report out-of-grammar speech instead of forcing a wrong match.
     */
    public static String grammarJson() {
        JSONArray a = new JSONArray();
        for (Cmd c : CMDS) a.put(c.phrase);
        a.put("[unk]");
        return a.toString();
    }

    private static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace("[unk]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * All commands present in an utterance, in order of appearance.
     *
     * Vosk can return several commands in one utterance
     * ("air conditioning on | fan up") and may pad with [unk], so we scan
     * rather than requiring an exact string equality.
     */
    public static List<Cmd> matchAll(String recognised) {
        List<Cmd> out = new ArrayList<>();
        String t = normalise(recognised);
        if (t.isEmpty()) return out;

        int cursor = 0;
        while (cursor < t.length()) {
            Cmd best = null;
            int bestAt = -1;
            for (Cmd c : CMDS) {
                int at = t.indexOf(c.phrase, cursor);
                if (at < 0) continue;
                boolean better = bestAt < 0
                        || at < bestAt
                        || (at == bestAt && c.phrase.length() > best.phrase.length());
                if (better) { bestAt = at; best = c; }
            }
            if (best == null) break;
            out.add(best);
            cursor = bestAt + best.phrase.length();
        }
        return out;
    }

    /** Execute one matched command against the climate UI. */
    public static ClimateService.Result execute(ClimateService svc, Cmd c) {
        switch (c.op) {
            case OP_ENSURE_ON:  return svc.ensure(c.viewId, true);
            case OP_ENSURE_OFF: return svc.ensure(c.viewId, false);
            case OP_TAP:        return svc.tap(c.viewId);
            case OP_TAP_N:      return svc.tapRepeat(c.viewId, c.arg);
            case OP_FAN_LEVEL:  return svc.setFanLevel(c.arg);
            default:            return ClimateService.Result.fail("unknown op " + c.op);
        }
    }

    private Commands() {}
}
