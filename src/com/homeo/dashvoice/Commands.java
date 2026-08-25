package com.homeo.dashvoice;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * The command vocabulary, backed by direct API calls to {@link BydAcApi}.
 *
 * <p>Every phrase resolves to a small closure ({@link Action}) that the
 * dispatcher runs against the API. A closure returns a {@link Result} that
 * knows whether it succeeded and what to display, so the UI can be honest.
 *
 * <p>Two jobs remain identical to v0.1:
 *   1. Produce the JSON grammar handed to Vosk.
 *   2. Match a recognised utterance to zero-or-more commands, in order.
 */
public final class Commands {

    /** Something a phrase can do. */
    public interface Action {
        Result run(BydAcApi api);
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        private Result(boolean s, String m) { success = s; message = m; }
        public static Result ok(String m)   { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    static final class Entry {
        final String phrase;
        final Action action;
        Entry(String phrase, Action action) { this.phrase = phrase; this.action = action; }
    }

    /**
     * Handy factory for a "toggle" phrase: only tap the underlying setter if
     * the current state differs from wanted, so "AC on" cannot switch off an
     * AC that's already running.
     */
    private static Action ensureStart(final boolean on) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                int cur = api.getStartState();
                if (cur == (on ? 1 : 0)) {
                    return Result.ok("AC already " + (on ? "on" : "off"));
                }
                boolean ok = on ? api.start() : api.stop();
                return ok ? Result.ok("AC " + (on ? "on" : "off"))
                          : Result.fail("start/stop refused");
            }
        };
    }

    private static Action ensureCompressor(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                if (api.getCompressor() == state) return Result.ok("compressor already");
                return api.setCompressor(state)
                    ? Result.ok("compressor " + (state == 1 ? "on" : "off"))
                    : Result.fail("compressor refused");
            }
        };
    }

    private static Action setFan(final int level) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setFan(level)
                    ? Result.ok("fan -> " + level)
                    : Result.fail("fan refused");
            }
        };
    }

    private static Action setTemp(final int zone, final int c) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                boolean ok = api.setTemp(zone, c);
                return ok ? Result.ok((zone == BydAcApi.ZONE_DRIVER ? "driver" : "passenger")
                                     + " temp -> " + c + "°C")
                          : Result.fail("temp refused (currently " + api.getTemp(zone) + ")");
            }
        };
    }

    private static Action stepTemp(final int zone, final int delta) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                boolean ok = api.stepTemp(zone, delta);
                return ok ? Result.ok((zone == BydAcApi.ZONE_DRIVER ? "driver" : "passenger")
                                     + " temp -> " + api.getTemp(zone) + "°C")
                          : Result.fail("temp step refused");
            }
        };
    }

    private static Action setCycle(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setCycleMode(mode)
                    ? Result.ok(mode == 1 ? "recirculate" : "fresh air")
                    : Result.fail("cycle refused");
            }
        };
    }

    private static Action setControl(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setControlMode(mode)
                    ? Result.ok(mode == 0 ? "auto mode" : "manual mode")
                    : Result.fail("mode refused");
            }
        };
    }

    private static Action setMaxCool(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setMaxCooling(state) ? Result.ok("max cooling " + state)
                                                : Result.fail("max cool refused");
            }
        };
    }

    private static Action setDefrostFront(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setDefrost(0, state) ? Result.ok("front defrost " + state)
                                                : Result.fail("defrost refused");
            }
        };
    }

    private static Action setDefrostRear(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setDefrost(1, state) ? Result.ok("rear defrost " + state)
                                                : Result.fail("defrost refused");
            }
        };
    }

    private static Action setVent(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setVentilation(state) ? Result.ok("ventilation " + state)
                                                 : Result.fail("ventilation refused");
            }
        };
    }

    private static Action setWindMode(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api) {
                return api.setWindMode(mode) ? Result.ok("vent mode -> " + mode)
                                             : Result.fail("wind mode refused");
            }
        };
    }

    /** The vocabulary. Order matters only for the grammar; matching handles overlap. */
    private static final Entry[] TABLE = {
        // Power
        new Entry("air conditioning on",  ensureStart(true)),
        new Entry("air conditioning off", ensureStart(false)),
        new Entry("ac on",                ensureStart(true)),
        new Entry("ac off",               ensureStart(false)),

        // Compressor
        new Entry("compressor on",  ensureCompressor(1)),
        new Entry("compressor off", ensureCompressor(0)),

        // Modes
        new Entry("auto mode",   setControl(0)),
        new Entry("manual mode", setControl(1)),
        new Entry("max cooling", setMaxCool(1)),
        new Entry("stop cooling",setMaxCool(0)),

        // Defrost
        new Entry("front defrost",     setDefrostFront(1)),
        new Entry("front defrost off", setDefrostFront(0)),
        new Entry("rear defrost",      setDefrostRear(1)),
        new Entry("rear defrost off",  setDefrostRear(0)),

        // Air source
        new Entry("recirculate", setCycle(1)),
        new Entry("fresh air",   setCycle(0)),

        // Fan (absolute levels)
        new Entry("fan one",   setFan(1)),
        new Entry("fan two",   setFan(2)),
        new Entry("fan three", setFan(3)),
        new Entry("fan four",  setFan(4)),
        new Entry("fan five",  setFan(5)),
        new Entry("fan six",   setFan(6)),
        new Entry("fan seven", setFan(7)),
        new Entry("fan max",   setFan(7)),
        new Entry("fan min",   setFan(1)),

        // Temperature - absolute (driver)
        new Entry("temperature seventeen", setTemp(BydAcApi.ZONE_DRIVER, 17)),
        new Entry("temperature eighteen",  setTemp(BydAcApi.ZONE_DRIVER, 18)),
        new Entry("temperature nineteen",  setTemp(BydAcApi.ZONE_DRIVER, 19)),
        new Entry("temperature twenty",    setTemp(BydAcApi.ZONE_DRIVER, 20)),
        new Entry("temperature twenty one",setTemp(BydAcApi.ZONE_DRIVER, 21)),
        new Entry("temperature twenty two",setTemp(BydAcApi.ZONE_DRIVER, 22)),
        new Entry("temperature twenty three", setTemp(BydAcApi.ZONE_DRIVER, 23)),
        new Entry("temperature twenty four",  setTemp(BydAcApi.ZONE_DRIVER, 24)),
        new Entry("temperature twenty five",  setTemp(BydAcApi.ZONE_DRIVER, 25)),
        new Entry("temperature twenty six",   setTemp(BydAcApi.ZONE_DRIVER, 26)),
        new Entry("temperature twenty seven", setTemp(BydAcApi.ZONE_DRIVER, 27)),
        new Entry("temperature twenty eight", setTemp(BydAcApi.ZONE_DRIVER, 28)),
        new Entry("temperature twenty nine",  setTemp(BydAcApi.ZONE_DRIVER, 29)),
        new Entry("temperature thirty",       setTemp(BydAcApi.ZONE_DRIVER, 30)),

        // Temperature - relative
        new Entry("warmer",         stepTemp(BydAcApi.ZONE_DRIVER,  1)),
        new Entry("colder",         stepTemp(BydAcApi.ZONE_DRIVER, -1)),
        new Entry("temperature up", stepTemp(BydAcApi.ZONE_DRIVER,  1)),
        new Entry("temperature down", stepTemp(BydAcApi.ZONE_DRIVER, -1)),
        new Entry("much warmer",    stepTemp(BydAcApi.ZONE_DRIVER,  3)),
        new Entry("much colder",    stepTemp(BydAcApi.ZONE_DRIVER, -3)),

        // Temperature - passenger side
        new Entry("passenger warmer",  stepTemp(BydAcApi.ZONE_PASSENGER,  1)),
        new Entry("passenger colder",  stepTemp(BydAcApi.ZONE_PASSENGER, -1)),

        // Vent direction (values 1=face, 5=foot, 0=defrost per Dolphin docs)
        new Entry("vent face", setWindMode(1)),
        new Entry("vent feet", setWindMode(5)),

        // Ventilation
        new Entry("ventilation on",  setVent(1)),
        new Entry("ventilation off", setVent(0)),
    };

    /** Phrases in registration order. */
    public static List<String> phrases() {
        List<String> out = new ArrayList<>(TABLE.length);
        for (Entry e : TABLE) out.add(e.phrase);
        return out;
    }

    /**
     * JSON array Vosk expects for a constrained grammar. "[unk]" lets the
     * decoder report out-of-grammar speech instead of forcing a wrong match.
     */
    public static String grammarJson() {
        JSONArray a = new JSONArray();
        for (Entry e : TABLE) a.put(e.phrase);
        a.put("[unk]");
        return a.toString();
    }

    private static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace("[unk]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * All commands present in an utterance, in order of appearance.
     * "AC on, fan five" -> two commands.
     */
    public static List<Entry> matchAll(String recognised) {
        List<Entry> out = new ArrayList<>();
        String t = normalise(recognised);
        if (t.isEmpty()) return out;

        int cursor = 0;
        while (cursor < t.length()) {
            Entry best = null;
            int bestAt = -1;
            for (Entry c : TABLE) {
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

    public static Result execute(BydAcApi api, Entry e) { return e.action.run(api); }

    private Commands() {}
}
