package local.claude.pie;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Right-edge pie. Two accessibility-overlay windows (they stay above the lock screen, shade,
 * keyboard and full-screen apps): a 32 px touch strip, and a non-touchable window the pie is
 * drawn in. The strip keeps the whole touch stream after the finger leaves it, so all
 * tracking happens there and the pie window never needs relayout.
 */
public final class PieService extends AccessibilityService {
    private static final String TAG = "ClaudePie";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private PieView pie;
    private View strip;
    private int screenRight;
    private boolean firedLong;
    private Layout.Slot[] outer;
    private android.content.SharedPreferences slotPrefs;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener slotsChanged =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(android.content.SharedPreferences p, String key) {
            if (Layout.KEY_ENABLED.equals(key)) { applyEnabled(); return; }
            outer = Layout.outer(PieService.this);
            pie.setOuter(outer);
        }
    };

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            int s = pie.selected();
            if (!pie.isOpen() || s < Layout.INNER.length) return;
            Layout.Slot slot = outer[s - Layout.INNER.length];
            if (slot.longPkg == null) return;
            firedLong = true;
            strip.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            pie.hide();
            launch(slot.longPkg, slot.longCls);
        }
    };

    @Override protected void onServiceConnected() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        wm = getSystemService(WindowManager.class);
        Rect screen = wm.getCurrentWindowMetrics().getBounds();
        screenRight = screen.right;

        outer = Layout.outer(this);
        slotPrefs = getSharedPreferences(Layout.PREFS, MODE_PRIVATE);
        slotPrefs.registerOnSharedPreferenceChangeListener(slotsChanged);
        pie = new PieView(this, outer);
        WindowManager.LayoutParams pl = params(screen.right - (Layout.R_OUTER + 1), 0,
                Layout.R_OUTER + 1, screen.height(), WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        pl.setTitle("ClaudePie");
        wm.addView(pie, pl);

        strip = new View(this);
        strip.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return onStripTouch(e); }
        });
        WindowManager.LayoutParams sl = params(screen.right - Layout.STRIP_W, Layout.STRIP_TOP,
                Layout.STRIP_W, Layout.STRIP_H, 0);
        sl.setTitle("ClaudePieStrip");
        wm.addView(strip, sl);
        applyEnabled();
        Log.i(TAG, "connected, screen " + screen);
    }

    /** Pie on/off from the settings switch: off = the strip lets every touch through to the app below. */
    private void applyEnabled() {
        if (strip == null) return;
        boolean on = slotPrefs.getBoolean(Layout.KEY_ENABLED, true);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) strip.getLayoutParams();
        int flags = on ? lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                       : lp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (flags != lp.flags) { lp.flags = flags; wm.updateViewLayout(strip, lp); }
        if (!on) pie.hide();
        Log.i(TAG, "pie " + (on ? "on" : "off"));
    }

    private static WindowManager.LayoutParams params(int x, int y, int w, int h, int extraFlags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | extraFlags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = x;
        p.y = y;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        return p;
    }

    private boolean onStripTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                firedLong = false;
                pie.show(Math.round(e.getRawY()));
                strip.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (firedLong || !pie.isOpen()) return true;
                if (pie.track(e.getRawX(), e.getRawY(), screenRight)) {
                    main.removeCallbacks(longPress);
                    if (pie.selected() != PieView.NONE) {
                        strip.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (pie.selected() >= Layout.INNER.length
                                && outer[pie.selected() - Layout.INNER.length].longPkg != null) main.postDelayed(longPress, Layout.LONG_PRESS_MS);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                main.removeCallbacks(longPress);
                if (firedLong || !pie.isOpen()) return true;
                pie.track(e.getRawX(), e.getRawY(), screenRight);
                int s = pie.selected();
                pie.hide();
                run(s);
                return true;
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                pie.hide();
                return true;
        }
        return true;
    }

    private void run(int s) {
        if (s == PieView.NONE) return;
        if (s < Layout.INNER.length) {
            switch (Layout.INNER[s].global) {
                case Layout.BACK: performGlobalAction(GLOBAL_ACTION_BACK); break;
                case Layout.HOME: performGlobalAction(GLOBAL_ACTION_HOME); break;
                case Layout.RECENTS: performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            }
            return;
        }
        Layout.Slot slot = outer[s - Layout.INNER.length];
        if (slot.pkg != null) launch(slot.pkg, slot.cls);
    }

    private void launch(String pkg, String cls) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent l = getPackageManager().getLaunchIntentForPackage(pkg);
            if (l == null) { Log.w(TAG, "cannot launch " + pkg, e); return; }
            l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(l);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        main.removeCallbacks(longPress);
        if (slotPrefs != null) slotPrefs.unregisterOnSharedPreferenceChangeListener(slotsChanged);
        if (wm != null) {
            if (strip != null) wm.removeViewImmediate(strip);
            if (pie != null) wm.removeViewImmediate(pie);
        }
        super.onDestroy();
    }
}
