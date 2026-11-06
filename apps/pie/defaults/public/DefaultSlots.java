package local.claude.pie;

/**
 * Public outer-ring defaults: all five slots start empty. Pick apps in the Pie Pro settings screen
 * (open Pie Pro from the app list). The colours are the ring colour of each slot position.
 */
final class DefaultSlots {
    static final Layout.Slot[] OUTER = {
        new Layout.Slot(null, null, 0xFF9266B4, null, null, 0xFFDB86F8),
        new Layout.Slot(null, null, 0xFF8DB2AA, null, null, 0xFFCF9288),
        new Layout.Slot(null, null, 0xFFB4FDE1, null, null, 0xFFA5D5F6),
        new Layout.Slot(null, null, 0xFFABC9EC, null, null, 0xFFA8ABC3),
        new Layout.Slot(null, null, 0xFFDF80A3, null, null, 0xFFA9FD91),
    };

    private DefaultSlots() {}
}
