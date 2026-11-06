package local.claude.hibreaklauncher;

/**
 * Public build settings (./build.sh --release, or any build without a private personal/ folder).
 * Neutral first run: no favorites, every app on the first page of the all-apps grid, the triangle-mosaic home picture.
 */
final class Flavor {
    private Flavor() {}

    static final boolean PUBLIC = true;
    /** Touch / draw diagnostics in logcat: off in public builds. */
    static final boolean DEBUG_LOG = false;

    /** Package names bookmarked on first run. Empty: the home screen explains how to add favorites. */
    static final String[] DEFAULT_BOOKMARKS = {};
    /** Packages kept on the first page of all apps on first run; the rest go to More. Empty: no sorting into More. */
    static final String[] DRAWER_MAIN = {};
    /** First-run app names, as package, name pairs. Empty: every app keeps its own name until renamed. */
    static final String[] DEFAULT_NAMES = {};
}
