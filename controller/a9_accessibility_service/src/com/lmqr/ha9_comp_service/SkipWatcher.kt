package com.lmqr.ha9_comp_service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Generic panel-freeze guard. The vendor HWC drops some app frames ("[POLICY] skip: No dirty
 * region find for commit"), leaving the e-ink panel on an old picture (Chrome after wake, the
 * launcher, ReadEra; camera-verified Oct 12 2026). While the screen is on, this follows only the
 * HWC's own warning lines and answers each skip with a short FrameNudger burst, which makes the
 * HWC commit the current frame. Reads no app content. System UID may read logs, no permission.
 * Capped: at most MAX_RUN_MS of continuous nudging, then a COOLDOWN_MS pause.
 */
class SkipWatcher(private val nudger: FrameNudger) {
    companion object {
        private const val TAG = "HiBreakSkipWatcher"
        private const val ENABLED = false
        private const val BURST_MS = 700L
        private const val MAX_RUN_MS = 6000L
        private const val COOLDOWN_MS = 2000L
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    private var runStart = 0L
    private var cooldownUntil = 0L
    private var lastBurst = 0L

    fun start() {

        if (!ENABLED) return   // controller65 change, deliberately not shipped (one change at a time)
        if (process != null) return
        val p = try {
            ProcessBuilder("logcat", "-T", "1", "-v", "brief", "hwcomposer:W", "*:S").redirectErrorStream(true).start()
        } catch (e: Exception) { Log.w(TAG, "logcat unavailable", e); return }
        process = p
        Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.contains("No dirty region")) main.post { onSkip() }
                    }
                }
            } catch (_: Exception) {}
        }, "HiBreakSkipWatcher").start()
    }

    private fun onSkip() {
        if (process == null) return
        val now = SystemClock.uptimeMillis()
        if (now < cooldownUntil) return
        if (now - lastBurst > BURST_MS + 200) runStart = now          // a new run of skips
        if (now - runStart > MAX_RUN_MS) { cooldownUntil = now + COOLDOWN_MS; return }
        if (now - lastBurst < 150) return                             // already nudging
        lastBurst = now
        nudger.burst(BURST_MS)
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
