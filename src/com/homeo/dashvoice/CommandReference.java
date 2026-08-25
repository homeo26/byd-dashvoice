package com.homeo.dashvoice;

/**
 * The on-screen command reference.
 *
 * <p>The grammar in {@link Commands} holds 128 entries, most of which are
 * pronunciation aliases — "fan two" also accepts "fan to" and "fan too"
 * because the small English Vosk model mishears digits. Listing all of them
 * would be noise, so each group here shows the phrasing worth remembering and
 * notes the alternatives in prose.
 *
 * <p>{@link Group#status} records what actually happens on this vehicle, so
 * the screen never advertises something the car refuses.
 */
final class CommandReference {

    enum Status { WORKS, BLOCKED, ABSENT }

    static final class Group {
        final String title;
        final String[] phrases;
        final String note;      // nullable
        final Status status;

        Group(String title, Status status, String note, String... phrases) {
            this.title = title;
            this.status = status;
            this.note = note;
            this.phrases = phrases;
        }
    }

    static final Group[] GROUPS = {

        new Group("Turn it on and off", Status.WORKS,
                "Also \"air conditioning on/off\" and \"turn on/off the ac\".",
                "ac on",
                "ac off"),

        new Group("Just say how you feel", Status.WORKS,
                "Sets a sensible temperature and fan together. Also \"i'm hot\", "
                + "\"too cold\", \"cool the car\", \"heat the car\".",
                "cool it down",
                "warm it up",
                "comfort mode"),

        new Group("Temperature", Status.WORKS,
                "Any value from seventeen to thirty. Also \"set temperature to "
                + "twenty four\". Relative: \"much warmer\", \"a bit colder\".",
                "temperature twenty two",
                "warmer",
                "cooler",
                "passenger warmer"),

        new Group("Fan speed", Status.WORKS,
                "Levels one to seven. Digit lookalikes are accepted, so "
                + "\"fan to\" and \"fan too\" both mean fan two.",
                "fan three",
                "fan max",
                "fan low",
                "fan up",
                "fan down"),

        new Group("Where the air goes", Status.WORKS,
                "Also \"air on face\", \"air on feet\", \"vent both\".",
                "vent face",
                "vent feet",
                "vent face and feet",
                "vent everywhere"),

        new Group("Demisting", Status.WORKS,
                "Front defrost uses max mode. Also \"clear the windshield\".",
                "defrost windshield",
                "max defrost",
                "rear defrost",
                "rear defrost off"),

        new Group("Air source", Status.WORKS,
                "Also \"recirculation\", \"outside air\".",
                "recirculate",
                "fresh air"),

        new Group("Modes", Status.WORKS,
                "\"stop cooling\" leaves the fan running.",
                "auto mode",
                "manual mode",
                "max cooling",
                "ventilation on",
                "fan only"),

        new Group("Compressor", Status.WORKS, null,
                "compressor on",
                "compressor off"),

        new Group("Windows", Status.BLOCKED,
                "Recognised and sent, but the car refuses to move them: "
                + "getWindowPermitState reads 0, a body-control interlock we "
                + "cannot override without the signature permission. Only the "
                + "driver window is even addressable; the others report 65535.",
                "close windows",
                "open driver window"),

        new Group("Sunroof", Status.ABSENT,
                "Not fitted to this car — the sunroof getters return 65535.",
                "open sunroof",
                "close sunroof"),
    };

    private CommandReference() {}
}
