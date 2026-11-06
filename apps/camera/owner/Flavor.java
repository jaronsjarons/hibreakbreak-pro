package local.claude.hibreakcamera;

/** Owner build settings (./build.sh). ./build.sh --release uses public/Flavor.java. */
final class Flavor {
    private Flavor() {}
    static final boolean PUBLIC = false;
    /** Accept the adb test extras (mfb, ais, curve, zsl, flush, cap, scene, pfps, shoot) on the launch intent. */
    static final boolean TEST_EXTRAS = true;
}
