package com.lmqr.ha9_comp_service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

/**
 * Keeps the e-ink panel following apps whose frames the stock HWC drops.
 *
 * Some readers (ReadEra's continuous-scroll view) post frames without damage the vendor
 * HWC accepts: it logs "No dirty region find for commit, skip" and the panel freezes on an
 * old page. Any full-screen layer change makes the HWC commit the whole composition, so for a
 * short burst after a touch this toggles an invisible (alpha 1/255) full-screen overlay.
 * Nothing is read from the screen; the overlay is not touchable or focusable.
 */
class FrameNudger(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var until = 0L
    private var odd = false
    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (SystemClock.uptimeMillis() >= until) { detach(); return }
            odd = !odd
            v.setBackgroundColor(if (odd) 0x02FFFFFF else 0x01FFFFFF)
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    companion object { const val PERIOD_MS = 120L }

    fun burst(durationMs: Long) {
        until = SystemClock.uptimeMillis() + durationMs
        if (view == null) attach()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun attach() {
        val v = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setBackgroundColor(0x01FFFFFF)
        }
        val params = WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { title = "HiBreak frame nudger" }
        try { wm.addView(v, params); view = v } catch (_: Exception) {}
    }

    fun detach() {
        handler.removeCallbacks(tick)
        view?.let { if (it.isAttachedToWindow) wm.removeViewImmediate(it) }
        view = null
    }
}
