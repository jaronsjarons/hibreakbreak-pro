package local.claude.pie;

/** Pie geometry and slots, matching Pie Control's side pie on the HiBreak Pro. Pixels are for 824 x 1648. */
final class Layout {
    // Trigger strip on the right edge (Pie Control's window: x 792-824, y 323-916).
    static final int STRIP_W = 32, STRIP_TOP = 323, STRIP_H = 593;

    // Pie radii: empty centre, inner ring, outer ring.
    static final int R_HOLE = 99, R_INNER = 230, R_OUTER = 360;
    static final int R_ICON_INNER = 166, R_ICON_OUTER = 310;
    static final int ICON = 76, BADGE = 38, BADGE_SHIFT = 55;

    static final int RING = 0xFFF8F8F8, SELECT = 0x7A000000, DIVIDER = 0xFFB4B4B4;
    static final long LONG_PRESS_MS = 700;
    /** Settings switch: false = the edge strip ignores touches (Pie off, Nov 6 2026). */
    static final String KEY_ENABLED = "pie_enabled";

    static final int BACK = 1, HOME = 2, RECENTS = 3;

    /** One slot: either a global action (with an asset icon) or an app, plus an optional long-press app. */
    static final class Slot {
        final int global; final String asset;
        final String pkg, cls; final int color;
        final String longPkg, longCls; final int longColor;
        Slot(int global, String asset, int color) {
            this.global = global; this.asset = asset; this.color = color;
            pkg = cls = longPkg = longCls = null; longColor = 0;
        }
        Slot(String pkg, String cls, int color, String longPkg, String longCls, int longColor) {
            global = 0; asset = null;
            this.pkg = pkg; this.cls = cls; this.color = color;
            this.longPkg = longPkg; this.longCls = longCls; this.longColor = longColor;
        }
    }

    /** Inner ring, top to bottom, 60 degrees each. */
    static final Slot[] INNER = {
        new Slot(BACK, "back.png", 0xFFFB7474),
        new Slot(HOME, "home.png", 0xFF69DCCF),
        new Slot(RECENTS, "recent.png", 0xFFB674D4),
    };

    /** Outer ring, top to bottom, 36 degrees each. Defaults come from defaults/public (or a private defaults/owner file). */
    static final Slot[] OUTER = DefaultSlots.OUTER;

    // ---- outer slots as chosen in Settings (stored as "pkg/cls", "" = empty; missing = DefaultSlots) ----

    static final String PREFS = "slots";

    static String tapKey(int i) { return "tap" + i; }
    static String holdKey(int i) { return "hold" + i; }

    /** The outer slots with the user's choices applied; ring colours stay with the slot position. */
    static Slot[] outer(android.content.Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        Slot[] out = new Slot[OUTER.length];
        for (int i = 0; i < OUTER.length; i++) {
            Slot d = OUTER[i];
            String[] tap = split(p.getString(tapKey(i), join(d.pkg, d.cls)));
            String[] hold = split(p.getString(holdKey(i), join(d.longPkg, d.longCls)));
            out[i] = new Slot(tap[0], tap[1], d.color, hold[0], hold[1], d.longColor);
        }
        return out;
    }

    private static String join(String pkg, String cls) { return pkg == null ? "" : pkg + "/" + cls; }

    private static String[] split(String s) {
        int k = s == null ? -1 : s.indexOf('/');
        return k <= 0 ? new String[2] : new String[] { s.substring(0, k), s.substring(k + 1) };
    }

    private Layout() {}
}
