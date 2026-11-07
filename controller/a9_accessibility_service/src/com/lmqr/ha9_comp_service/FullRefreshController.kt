package com.lmqr.ha9_comp_service

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.lmqr.ha9_comp_service.command_runners.CommandRunner

/** Visible black/white redraw in HD mode. No waveform, voltage, or calibration
 * tuning. Optical testing must establish whether this clears residual images. */
class FullRefreshController(private val service: A9AccessibilityService,
    private val commands: CommandRunner, private val restore: () -> Unit,
    private val restoreMode: () -> Unit = restore, private val inFastMode: () -> Boolean = { false }) {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var running = false
    fun refresh() {
        if (running || !service.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        running = true
        // Phase lengths are tunable for camera tests (hidden prefs flash_black_ms / flash_white_ms).
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)
        val black = prefs.getInt("flash_black_ms", 400).coerceIn(80, 1000).toLong()
        val white = prefs.getInt("flash_white_ms", 400).coerceIn(80, 1000).toLong()
        val settle = prefs.getInt("flash_settle_ms", 400).coerceIn(0, 1000).toLong()
        // Camera, Oct 25 2026: leaving Extreme/Hyper straight for HD, the black frame was lost in most
        // flashes (1/8 Extreme, 4/8 Hyper, 8/8 from Normal). Passing through Normal for 300 ms first
        // fixed it (8/8 both; 200 ms 7/8, 120 ms 2/8). Only needed when leaving a fast mode.
        val pre = if (inFastMode()) prefs.getInt("flash_pre_ms", 300).coerceIn(0, 1000).toLong() else 0L
        if (pre > 0) commands.runCommands(arrayOf("b"))
        handler.postDelayed({ if (running) commands.runCommands(arrayOf("s")) }, pre)
        handler.postDelayed({
            if (!running) return@postDelayed
            val view = View(service).apply { setBackgroundColor(Color.BLACK) }
            val params = WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
            try {
                service.getSystemService(WindowManager::class.java).addView(view,params)
                overlay = view
                // Oct 21 2026 (owner: full refresh took too long): black 650 -> 400 ms, white 650 -> 400 ms,
                // restore 1900 -> 1200 ms. ~0.4 s per phase still covers the e-ink drive time.
                handler.postDelayed({ view.setBackgroundColor(Color.WHITE) },black)
                handler.postDelayed({ removeOverlay() },black+white)
                handler.postDelayed({ running=false;restore() },black+white+settle)
            } catch(e: Exception) { cancel();restore() }
        },pre+150)
    }
    /**
     * Shorter clean for after scrolling: clean waveform (HD), one white frame, then the
     * content is redrawn in the clean mode before the app's own mode is restored.
     */
    fun lightRefresh(restoreMs: Long = 1600L) {
        if (running || !service.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        running = true
        commands.runCommands(arrayOf("s"))
        handler.postDelayed({
            if (!running) return@postDelayed
            val view = View(service).apply { setBackgroundColor(Color.WHITE) }
            val params = WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
            try {
                service.getSystemService(WindowManager::class.java).addView(view,params)
                overlay = view
                handler.postDelayed({ removeOverlay() },300)
                // Mode only: the image tone was never changed for a quick clean.
                handler.postDelayed({ running=false;restoreMode() },restoreMs.coerceIn(400L, 3000L))
            } catch(e: Exception) { cancel();restore() }
        },120)
    }
    private fun removeOverlay() {
        overlay?.let { if(it.isAttachedToWindow) service.getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }
    fun cancel() {
        running=false;handler.removeCallbacksAndMessages(null);removeOverlay()
    }
}
