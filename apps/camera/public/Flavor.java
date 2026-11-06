package local.claude.hibreakcamera;

/** Public build settings (./build.sh --release). */
final class Flavor {
    private Flavor() {}
    static final boolean PUBLIC = true;
    /** Off: other apps cannot change capture settings or make the camera shoot via launch-intent extras. */
    static final boolean TEST_EXTRAS = false;
}
