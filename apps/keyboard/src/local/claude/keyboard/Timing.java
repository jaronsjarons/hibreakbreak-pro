package local.claude.keyboard;

import android.os.SystemClock;
import android.util.Log;

/**
 * Optional typing-latency log (Nov 2026). Off unless enabled with: adb shell setprop log.tag.FastKeysTiming DEBUG
 * Logs the touch's own event time and when the suggestion strip is drawn, so key-to-screen delay can be measured on
 * the phone against the e-ink driver's panel-update log. Nothing typed is logged.
 */
final class Timing {
    static final String TAG = "FastKeysTiming";

    private Timing() {}

    static boolean on() { return Log.isLoggable(TAG, Log.DEBUG); }

    static void down(long eventUptime) {
        if (on()) Log.d(TAG, "down event=" + eventUptime + " now=" + SystemClock.uptimeMillis());
    }

    static void stripDrawn() {
        if (on()) Log.d(TAG, "strip now=" + SystemClock.uptimeMillis());
    }
}
