package com.lmqr.ha9_comp_service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.lmqr.ha9_comp_service.command_runners.CommandRunner

/** E-ink keeps the last frame after power-off, so the "Shutting down" box stayed on the
 * panel (owner, Oct 26 2026). On ACTION_SHUTDOWN (also sent before a reboot) cover the screen,
 * clean it black -> white in HD mode, and leave plain white. Android waits for this receiver
 * (goAsync, ~1.3 s) before it continues shutting down. The overlay is never removed.
 */
class ShutdownWhite(private val service: A9AccessibilityService, private val commands: CommandRunner,
    private val beforeCover: () -> Unit) : BroadcastReceiver() {
    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN || cover != null) return
        val pending = goAsync()
        FlashLog.add(service, "shutdown-white")
        beforeCover()
        commands.runCommands(arrayOf("s"))
        val view = View(service).apply { setBackgroundColor(Color.BLACK) }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE).apply {
            // Fill the status bar strip and any cutout too: without this the top edge stayed dark (owner, Oct 26).
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            service.getSystemService(WindowManager::class.java).addView(view, params)
            cover = view
        } catch (e: Exception) {
            pending.finish(); return
        }
        // Same black/white phase lengths as the manual full refresh (~0.4 s panel drive each).
        handler.postDelayed({ view.setBackgroundColor(Color.WHITE) }, 450)
        handler.postDelayed({ pending.finish() }, 1300)
    }
}
