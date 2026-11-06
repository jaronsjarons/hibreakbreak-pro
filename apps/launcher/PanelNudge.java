package local.claude.hibreaklauncher;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

/**
 * Makes every launcher change reach the e-ink panel. The vendor HWC drops some app frames
 * as "No dirty region" (measured: Home reset 2 commits / 8 skips, return from app 2 / 9), so the
 * panel kept showing the old screen. A separate full-screen, untouchable sub-window whose colour
 * flips between alpha 1/255 and 2/255 makes the whole composition dirty, so the HWC commits the
 * current frame. Invisible.
 * Flips are spaced (150, 650, 1400 ms after the last change) rather than every 100 ms: a
 * Normal-mode update takes ~0.5 s and back-to-back full-screen updates overlapping it are the
 * suspected cause of rare stale screens / ghost keyboards in the 2-hour soak (Oct 12 2026).
 */
final class PanelNudge {
    private static final long[] FLIPS_MS = {150, 650, 1400};
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ColorDrawable color = new ColorDrawable(0x01FFFFFF);
    private PopupWindow window;
    private boolean odd;

    private final Runnable flip = new Runnable() {
        @Override public void run() {
            if (window == null || !window.isShowing()) return;
            odd = !odd;
            color.setColor(odd ? 0x02FFFFFF : 0x01FFFFFF);
            window.getContentView().invalidate();
        }
    };

    PanelNudge(Activity activity) { this.activity = activity; }

    /** Call after any visible change. */
    void burst() {
        if (!ensureShown()) { handler.postDelayed(this::burst, 50); return; }
        handler.removeCallbacks(flip);              // restart the schedule from the latest change
        for (long d : FLIPS_MS) handler.postDelayed(flip, d);
    }

    private boolean ensureShown() {
        if (window != null && window.isShowing()) return true;
        View anchor = activity.getWindow().getDecorView();
        if (anchor.getWindowToken() == null || activity.isFinishing()) return false;
        View content = new View(activity);
        content.setBackground(color);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        window = new PopupWindow(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, false);
        window.setTouchable(false);
        window.setFocusable(false);
        window.setOutsideTouchable(false);
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setClippingEnabled(false);
        window.setAnimationStyle(0);
        window.setBackgroundDrawable(null);
        try { window.showAtLocation(anchor, Gravity.NO_GRAVITY, 0, 0); }
        catch (RuntimeException e) { window = null; return false; }
        return true;
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (window != null) { try { window.dismiss(); } catch (RuntimeException ignored) {} window = null; }
    }
}
