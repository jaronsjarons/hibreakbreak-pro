package com.lmqr.ha9_comp_service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * One clean refresh after scrolling stops, so fast-mode scrolling leaves no ghosting.
 *
 * Signals, neither of which reads screen content:
 *  - TYPE_VIEW_SCROLLED accessibility events (package name only);
 *  - in reading apps only, ACTION_OUTSIDE from a 1x1 overlay with FLAG_WATCH_OUTSIDE_TOUCH:
 *    Android reports *that* a touch went down elsewhere, never where (other apps' coordinates
 *    are zeroed). Readers that draw pages on a canvas send no scroll events, so this is the
 *    only way to notice their scrolling.
 * The clean waits until activity has been quiet for a moment and is skipped while the
 * keyboard is visible.
 */
class ScrollClean(
    private val service: AccessibilityService,
    private val preferences: SharedPreferences,
    private val keyboardVisible: () -> Boolean,
    private val clean: (light: Boolean) -> Unit,
) {
    companion object {
        // off | reading | all. New key, default off (Oct 11 2026): stock firmware never flashed
        // after scrolling; it relied on the kernel's auto clean (clean_a2) in Normal mode.
        const val PREF = "clean_after_scroll_v2"
        const val PREF_STYLE = "clean_style"          // light | full
        const val PREF_READERS = "reading_apps"
        // Touch-down cleaning cannot tell a scroll from a tap, so every menu tap flashed in
        // ReadEra (owner: "glitchy"). Off by default; scroll-event cleaning is unaffected.
        const val PREF_TOUCH = "clean_after_touch_reading"
        // Live scrolling: keep the panel following readers whose frames the HWC skips.
        const val PREF_LIVE = "live_scroll_reading"
        private const val NUDGE_MS = 2600L
        val DEFAULT_READERS = setOf(
            "org.readera", "org.readera.premium", "org.koreader.launcher", "eu.kanade.tachiyomi.j2k",
            "com.amazon.kindle", "com.google.android.apps.books", "com.flyersoft.moonreader",
            "com.flyersoft.moonreaderp", "org.ebookdroid", "com.foobnix.pdf.reader", "com.foobnix.pro.pdf.reader",
            "org.coolreader", "com.gitlab.mudlej.MjPdfReader")
        // Home apps whose frames the HWC also drops ("No dirty region"): HiBreak Launcher and
        // NoLauncher received every touch but the panel froze (Oct 12 2026). Always kept live.
        val ALWAYS_LIVE = setOf("local.claude.hibreaklauncher", "com.saranomy.nolauncher")
        private const val SCROLL_IDLE_MS = 700L
        private const val TOUCH_IDLE_MS = 2900L  // touch-down only: after drag, fling and nudging
    }

    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val pending = Runnable { if (!keyboardVisible()) clean(preferences.getString(PREF_STYLE, "light") != "full") }
    private var watcher: View? = null
    val nudger = FrameNudger(service)   // shared with SkipWatcher
    private var foreground = ""

    private fun mode() = preferences.getString(PREF, "off") ?: "off"
    private fun readers() = preferences.getStringSet(PREF_READERS, DEFAULT_READERS) ?: DEFAULT_READERS

    fun onAppChange(pkg: String) {
        if (pkg == foreground) return
        foreground = pkg
        handler.removeCallbacks(pending)
        val wantTouch = (mode() != "off" && preferences.getBoolean(PREF_TOUCH, false)) || preferences.getBoolean(PREF_LIVE, true)
        if ((wantTouch && pkg in readers()) || (preferences.getBoolean(PREF_LIVE, true) && pkg in ALWAYS_LIVE)) attachWatcher()
        else detachWatcher()
    }

    fun onScroll(pkg: String?) {
        if (pkg == null || pkg == service.packageName || pkg == "com.android.systemui") return
        when (mode()) {
            "all" -> schedule(SCROLL_IDLE_MS)
            "reading" -> if (pkg in readers()) schedule(SCROLL_IDLE_MS)
        }
    }

    private fun schedule(delay: Long) {
        if (keyboardVisible()) return
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, delay)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWatcher() {
        if (watcher != null) return
        val view = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    if (preferences.getBoolean(PREF_LIVE, true)) nudger.burst(NUDGE_MS)
                    if (mode() != "off" && preferences.getBoolean(PREF_TOUCH, false)) schedule(TOUCH_IDLE_MS)
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            title = "HiBreak reading touch watcher"
        }
        try { wm.addView(view, params); watcher = view } catch (_: Exception) {}
    }

    private fun detachWatcher() {
        nudger.detach()
        watcher?.let { if (it.isAttachedToWindow) wm.removeViewImmediate(it) }
        watcher = null
    }

    fun stop() {
        handler.removeCallbacks(pending)
        detachWatcher()
        foreground = ""
    }
}
