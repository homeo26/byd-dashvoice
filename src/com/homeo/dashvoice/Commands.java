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

    /** Something a phrase can do. Both APIs are passed; either may be null. */
    public interface Action {
        Result run(BydAcApi ac, BydBodyworkApi body);
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
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
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
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                if (api.getCompressor() == state) return Result.ok("compressor already");
                return api.setCompressor(state)
                    ? Result.ok("compressor " + (state == 1 ? "on" : "off"))
                    : Result.fail("compressor refused");
            }
        };
    }

    private static Action setFan(final int level) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setFan(level)
                    ? Result.ok("fan -> " + level)
                    : Result.fail("fan refused");
            }
        };
    }

    private static Action setTemp(final int zone, final int c) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                boolean ok = api.setTemp(zone, c);
                return ok ? Result.ok((zone == BydAcApi.ZONE_DRIVER ? "driver" : "passenger")
                                     + " temp -> " + c + "°C")
                          : Result.fail("temp refused (currently " + api.getTemp(zone) + ")");
            }
        };
    }

    private static Action stepTemp(final int zone, final int delta) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                boolean ok = api.stepTemp(zone, delta);
                return ok ? Result.ok((zone == BydAcApi.ZONE_DRIVER ? "driver" : "passenger")
                                     + " temp -> " + api.getTemp(zone) + "°C")
                          : Result.fail("temp step refused");
            }
        };
    }

    private static Action setCycle(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setCycleMode(mode)
                    ? Result.ok(mode == 1 ? "recirculate" : "fresh air")
                    : Result.fail("cycle refused");
            }
        };
    }

    private static Action setControl(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setControlMode(mode)
                    ? Result.ok(mode == 0 ? "auto mode" : "manual mode")
                    : Result.fail("mode refused");
            }
        };
    }

    private static Action setMaxCool(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setMaxCooling(state) ? Result.ok("max cooling " + state)
                                                : Result.fail("max cool refused");
            }
        };
    }

    private static Action setDefrostFront(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setDefrost(0, state) ? Result.ok("front defrost " + state)
                                                : Result.fail("defrost refused");
            }
        };
    }

    private static Action setDefrostRear(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setDefrost(1, state) ? Result.ok("rear defrost " + state)
                                                : Result.fail("defrost refused");
            }
        };
    }

    private static Action setVent(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setVentilation(state) ? Result.ok("ventilation " + state)
                                                 : Result.fail("ventilation refused");
            }
        };
    }

    private static Action setWindMode(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setWindMode(mode) ? Result.ok("vent mode -> " + mode)
                                             : Result.fail("wind mode refused");
            }
        };
    }

    /** Combined action: max defrost mode. Configures vent+fan+fresh air. */
    private static Action maxDefrost() {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                if (api.getStartState() == 0) api.start();
                boolean ok = api.engageMaxDefrost();
                return ok ? Result.ok("max defrost engaged")
                          : Result.fail("defrost refused");
            }
        };
    }

    /** Combined action: fast cool. Sets low temp, fan max, fresh air. */
    private static Action fastCool() {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                if (api.getStartState() == 0) api.start();
                api.setControlMode(1);              // manual so temp/fan sticks
                api.setTemp(BydAcApi.ZONE_DRIVER, 17);
                api.setFan(7);
                api.setCycleMode(1);                // recirc for faster cooling
                api.setCompressor(1);
                return Result.ok("cooling maximum");
            }
        };
    }

    /** Combined action: fast heat. Sets high temp, fan max. */
    private static Action fastHeat() {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                if (api.getStartState() == 0) api.start();
                api.setControlMode(1);
                api.setTemp(BydAcApi.ZONE_DRIVER, 30);
                api.setFan(7);
                api.setCycleMode(1);
                return Result.ok("heating maximum");
            }
        };
    }

    /** Combined action: comfort — auto mode, moderate temp. */
    private static Action comfort() {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                if (api.getStartState() == 0) api.start();
                api.setControlMode(0);              // auto
                api.setTemp(BydAcApi.ZONE_DRIVER, 22);
                api.setTemp(BydAcApi.ZONE_PASSENGER, 22);
                return Result.ok("comfort mode 22°C auto");
            }
        };
    }

    private static Action setWind(final int mode) {
        return new Action() {
            @Override public Result run(BydAcApi api, BydBodyworkApi body) {
                return api.setWindMode(mode)
                    ? Result.ok("wind mode -> " + mode)
                    : Result.fail("wind mode refused");
            }
        };
    }

    /* ---- Bodywork factories ---- */

    private static Action winAll(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi ac, BydBodyworkApi body) {
                if (body == null) return Result.fail("bodywork permission not granted");
                boolean ok = body.setAllWindows(state, state, state, state);
                String label = (state == BydBodyworkApi.WIN_OPEN) ? "opening"
                             : (state == BydBodyworkApi.WIN_CLOSE) ? "closing"
                             : "stopping";
                return ok ? Result.ok(label + " all windows")
                          : Result.fail(label + " refused");
            }
        };
    }

    private static Action winOne(final int pos, final int state) {
        return new Action() {
            @Override public Result run(BydAcApi ac, BydBodyworkApi body) {
                if (body == null) return Result.fail("bodywork permission not granted");
                String[] names = {"", "driver", "passenger", "rear left", "rear right"};
                String label = (state == BydBodyworkApi.WIN_OPEN) ? "opening" : "closing";
                boolean ok = body.setWindow(pos, state);
                return ok ? Result.ok(label + " " + names[pos] + " window")
                          : Result.fail("window refused");
            }
        };
    }

    private static Action sunroof(final int state) {
        return new Action() {
            @Override public Result run(BydAcApi ac, BydBodyworkApi body) {
                if (body == null) return Result.fail("bodywork permission not granted");
                boolean ok = body.setSunroof(state);
                String label = state == 1 ? "opening" : state == 2 ? "closing" : "stopping";
                return ok ? Result.ok(label + " sunroof")
                          : Result.fail("sunroof refused");
            }
        };
    }

    /** The vocabulary. Order matters only for the grammar; matching handles overlap. */
    private static final Entry[] TABLE = {
        // ---- Power ----
        new Entry("air conditioning on",     ensureStart(true)),
        new Entry("air conditioning off",    ensureStart(false)),
        new Entry("ac on",                   ensureStart(true)),
        new Entry("ac off",                  ensureStart(false)),
        new Entry("turn on the ac",          ensureStart(true)),
        new Entry("turn off the ac",         ensureStart(false)),
        new Entry("turn on air conditioning", ensureStart(true)),
        new Entry("turn off air conditioning",ensureStart(false)),

        // ---- Combined actions (natural language) ----
        new Entry("cool it down",     fastCool()),
        new Entry("i am hot",         fastCool()),
        new Entry("i'm hot",          fastCool()),
        new Entry("it's hot",         fastCool()),
        new Entry("its hot",          fastCool()),
        new Entry("too hot",          fastCool()),
        new Entry("cool the car",     fastCool()),

        new Entry("warm it up",       fastHeat()),
        new Entry("i am cold",        fastHeat()),
        new Entry("i'm cold",         fastHeat()),
        new Entry("it's cold",        fastHeat()),
        new Entry("its cold",         fastHeat()),
        new Entry("too cold",         fastHeat()),
        new Entry("heat the car",     fastHeat()),

        new Entry("comfort mode",     comfort()),
        new Entry("comfortable",      comfort()),

        // ---- Compressor ----
        new Entry("compressor on",  ensureCompressor(1)),
        new Entry("compressor off", ensureCompressor(0)),

        // ---- Modes ----
        new Entry("auto mode",       setControl(0)),
        new Entry("automatic",       setControl(0)),
        new Entry("manual mode",     setControl(1)),
        new Entry("max cooling",     setMaxCool(1)),
        new Entry("maximum cooling", setMaxCool(1)),
        new Entry("stop cooling",    setMaxCool(0)),

        // ---- Defrost (max mode via setAcDefrostState) ----
        new Entry("defrost windshield",  maxDefrost()),
        new Entry("defrost the windshield", maxDefrost()),
        new Entry("max defrost",         maxDefrost()),
        new Entry("clear the windshield", maxDefrost()),
        new Entry("front defrost",       maxDefrost()),

        // Explicit rear defrost via setDefrost
        new Entry("rear defrost",        new Action(){
            public Result run(BydAcApi a, BydBodyworkApi body){ return a.setDefrost(1,1)?Result.ok("rear defrost on"):Result.fail("rear defrost refused"); }
        }),
        new Entry("rear defrost off",    new Action(){
            public Result run(BydAcApi a, BydBodyworkApi body){ return a.setDefrost(1,0)?Result.ok("rear defrost off"):Result.fail("rear defrost refused"); }
        }),

        // ---- Air source ----
        new Entry("recirculate",       setCycle(1)),
        new Entry("recirculation",     setCycle(1)),
        new Entry("recirculation on",  setCycle(1)),
        new Entry("fresh air",         setCycle(0)),
        new Entry("outside air",       setCycle(0)),
        new Entry("recirculation off", setCycle(0)),

        // ---- Fan (absolute levels; Vosk small-en mishears digits, so aliases) ----
        new Entry("fan one",       setFan(1)),
        new Entry("fan won",       setFan(1)),
        new Entry("fan two",       setFan(2)),
        new Entry("fan to",        setFan(2)),
        new Entry("fan too",       setFan(2)),
        new Entry("fan three",     setFan(3)),
        new Entry("fan free",      setFan(3)),
        new Entry("fan tree",      setFan(3)),
        new Entry("fan four",      setFan(4)),
        new Entry("fan for",       setFan(4)),
        new Entry("fan five",      setFan(5)),
        new Entry("fan hive",      setFan(5)),
        new Entry("fan six",       setFan(6)),
        new Entry("fan seven",     setFan(7)),
        new Entry("fan max",       setFan(7)),
        new Entry("fan maximum",   setFan(7)),
        new Entry("fan full",      setFan(7)),
        new Entry("fan min",       setFan(1)),
        new Entry("fan minimum",   setFan(1)),
        new Entry("fan low",       setFan(1)),
        new Entry("fan medium",    setFan(4)),
        new Entry("fan high",      setFan(6)),

        // Relative fan
        new Entry("fan up",         new Action(){
            public Result run(BydAcApi a, BydBodyworkApi body){
                int n = a.getWindLevel(); int t = Math.min(BydAcApi.MAX_FAN, (n<1?1:n)+1);
                return a.setFan(t)?Result.ok("fan -> "+t):Result.fail("fan refused");
            }
        }),
        new Entry("fan down",       new Action(){
            public Result run(BydAcApi a, BydBodyworkApi body){
                int n = a.getWindLevel(); int t = Math.max(BydAcApi.MIN_FAN, (n<1?1:n)-1);
                return a.setFan(t)?Result.ok("fan -> "+t):Result.fail("fan refused");
            }
        }),

        // ---- Absolute temperature (driver) ----
        new Entry("temperature seventeen",      setTemp(BydAcApi.ZONE_DRIVER, 17)),
        new Entry("temperature eighteen",       setTemp(BydAcApi.ZONE_DRIVER, 18)),
        new Entry("temperature nineteen",       setTemp(BydAcApi.ZONE_DRIVER, 19)),
        new Entry("temperature twenty",         setTemp(BydAcApi.ZONE_DRIVER, 20)),
        new Entry("temperature twenty one",     setTemp(BydAcApi.ZONE_DRIVER, 21)),
        new Entry("temperature twenty two",     setTemp(BydAcApi.ZONE_DRIVER, 22)),
        new Entry("temperature twenty three",   setTemp(BydAcApi.ZONE_DRIVER, 23)),
        new Entry("temperature twenty four",    setTemp(BydAcApi.ZONE_DRIVER, 24)),
        new Entry("temperature twenty five",    setTemp(BydAcApi.ZONE_DRIVER, 25)),
        new Entry("temperature twenty six",     setTemp(BydAcApi.ZONE_DRIVER, 26)),
        new Entry("temperature twenty seven",   setTemp(BydAcApi.ZONE_DRIVER, 27)),
        new Entry("temperature twenty eight",   setTemp(BydAcApi.ZONE_DRIVER, 28)),
        new Entry("temperature twenty nine",    setTemp(BydAcApi.ZONE_DRIVER, 29)),
        new Entry("temperature thirty",         setTemp(BydAcApi.ZONE_DRIVER, 30)),
        // "set to N" natural phrasing
        new Entry("set temperature to twenty",         setTemp(BydAcApi.ZONE_DRIVER, 20)),
        new Entry("set temperature to twenty two",     setTemp(BydAcApi.ZONE_DRIVER, 22)),
        new Entry("set temperature to twenty four",    setTemp(BydAcApi.ZONE_DRIVER, 24)),
        new Entry("set temperature to twenty five",    setTemp(BydAcApi.ZONE_DRIVER, 25)),
        new Entry("set temperature to twenty six",     setTemp(BydAcApi.ZONE_DRIVER, 26)),

        // Relative temperature
        new Entry("warmer",           stepTemp(BydAcApi.ZONE_DRIVER,  1)),
        new Entry("colder",           stepTemp(BydAcApi.ZONE_DRIVER, -1)),
        new Entry("cooler",           stepTemp(BydAcApi.ZONE_DRIVER, -1)),
        new Entry("temperature up",   stepTemp(BydAcApi.ZONE_DRIVER,  1)),
        new Entry("temperature down", stepTemp(BydAcApi.ZONE_DRIVER, -1)),
        new Entry("much warmer",      stepTemp(BydAcApi.ZONE_DRIVER,  3)),
        new Entry("much colder",      stepTemp(BydAcApi.ZONE_DRIVER, -3)),
        new Entry("much cooler",      stepTemp(BydAcApi.ZONE_DRIVER, -3)),
        new Entry("a bit warmer",     stepTemp(BydAcApi.ZONE_DRIVER,  1)),
        new Entry("a bit colder",     stepTemp(BydAcApi.ZONE_DRIVER, -1)),
        new Entry("make it warmer",   stepTemp(BydAcApi.ZONE_DRIVER,  2)),
        new Entry("make it colder",   stepTemp(BydAcApi.ZONE_DRIVER, -2)),
        new Entry("make it cooler",   stepTemp(BydAcApi.ZONE_DRIVER, -2)),

        // Passenger side
        new Entry("passenger warmer",   stepTemp(BydAcApi.ZONE_PASSENGER,  1)),
        new Entry("passenger colder",   stepTemp(BydAcApi.ZONE_PASSENGER, -1)),
        new Entry("passenger cooler",   stepTemp(BydAcApi.ZONE_PASSENGER, -1)),

        // ---- Vent direction (verified via UI selection sweep on this unit) ----
        new Entry("vent face",           setWind(BydAcApi.WIND_FACE)),
        new Entry("air on face",         setWind(BydAcApi.WIND_FACE)),
        new Entry("vent feet",           setWind(BydAcApi.WIND_FOOT)),
        new Entry("vent foot",           setWind(BydAcApi.WIND_FOOT)),
        new Entry("air on feet",         setWind(BydAcApi.WIND_FOOT)),
        new Entry("vent face and feet",  setWind(BydAcApi.WIND_FACE_FOOT)),
        new Entry("vent both",           setWind(BydAcApi.WIND_FACE_FOOT)),
        new Entry("vent everywhere",     setWind(BydAcApi.WIND_ALL)),
        new Entry("vent all",            setWind(BydAcApi.WIND_ALL)),

        // ---- Ventilation (fan only, no cooling) ----
        new Entry("ventilation on",   setVent(1)),
        new Entry("ventilation off",  setVent(0)),
        new Entry("fan only",         setVent(1)),

        // ---- Windows (via BydBodyworkApi; requires BYDAUTO_BODYWORK_COMMON) ----
        new Entry("open windows",       winAll(BydBodyworkApi.WIN_OPEN)),
        new Entry("open all windows",   winAll(BydBodyworkApi.WIN_OPEN)),
        new Entry("close windows",      winAll(BydBodyworkApi.WIN_CLOSE)),
        new Entry("close all windows",  winAll(BydBodyworkApi.WIN_CLOSE)),
        new Entry("stop windows",       winAll(BydBodyworkApi.WIN_STOP)),

        new Entry("open driver window",     winOne(BydBodyworkApi.WIN_DRIVER,    BydBodyworkApi.WIN_OPEN)),
        new Entry("close driver window",    winOne(BydBodyworkApi.WIN_DRIVER,    BydBodyworkApi.WIN_CLOSE)),
        new Entry("open passenger window",  winOne(BydBodyworkApi.WIN_PASSENGER, BydBodyworkApi.WIN_OPEN)),
        new Entry("close passenger window", winOne(BydBodyworkApi.WIN_PASSENGER, BydBodyworkApi.WIN_CLOSE)),

        // Sunroof
        new Entry("open sunroof",   sunroof(1)),
        new Entry("close sunroof",  sunroof(2)),
        new Entry("stop sunroof",   sunroof(0)),
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
     * True if the text already ends with a complete command phrase.
     *
     * <p>Used by {@link VoskEngine} to commit as soon as a partial result
     * forms a whole command, instead of waiting for the recogniser's own
     * end-of-utterance decision. With a constrained grammar this is safe:
     * the only things that can be recognised are command words.
     */
    public static boolean endsWithCommand(String recognised) {
        String t = normalise(recognised);
        if (t.isEmpty()) return false;
        for (Entry c : TABLE) {
            if (t.endsWith(c.phrase)) return true;
        }
        return false;
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

    public static Result execute(BydAcApi ac, BydBodyworkApi body, Entry e) {
        return e.action.run(ac, body);
    }

    private Commands() {}
}
