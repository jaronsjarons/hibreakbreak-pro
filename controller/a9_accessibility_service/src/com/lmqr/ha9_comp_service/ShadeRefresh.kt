package com.lmqr.ha9_comp_service

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * One quick (white-only) clean after the notification shade settles (opened or closed), clearing the
 * ghosting its grey panels leave on the e-ink display.
 *
 * Closing over an app that announces itself (e.g. Niagara) is seen as that app's window
 * event: notificationTimeout coalescing drops SystemUI's own close event ~80 ms earlier.
 * Over apps that do not announce themselves, SystemUI's close event looks like an open,
 * so the next app event within SHADE_TTL_MS may cause one extra refresh.
 *
 * Detection uses only the shade window title that SystemUI announces in its own resources
 * ("Notification shade." / "Quick settings."), compared and discarded. Notification text
 * that the same events carry is never stored or logged. Lock-screen events are ignored.
 */
class ShadeRefresh(
    private val service: A9AccessibilityService,
    private val preferences: SharedPreferences,
    private val refresh: () -> Unit,
) {
    companion object {
        // New key, default off (Oct 11 2026): with the light shade overlay the clean's flash and
        // ~1.6 s in slow HD mode cost more than the little ghosting it removes.
        const val PREF = "shade_clean_light_shade"
        private const val SYSTEMUI = "com.android.systemui"
        private const val SETTLE_MS = 500L
        // lightRefresh takes ~1.7 s; a change during it is refreshed after it ends.
        private const val REFRESH_MS = 1800L
        private const val SHADE_TTL_MS = 120_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = Runnable { lastRefresh = SystemClock.uptimeMillis(); refresh() }
    private var lastRefresh = -REFRESH_MS
    private var shadeSeenAt = 0L
    private val titles: Set<String> by lazy { loadTitles() }

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName || !preferences.getBoolean(PREF, false)) return
        val now = SystemClock.uptimeMillis()
        if (pkg == SYSTEMUI) {
            val title = event.text.firstOrNull()?.toString() ?: return
            if (title !in titles) return
            shadeSeenAt = now
            schedule(now)
        } else if (shadeSeenAt != 0L) {
            // First app window after the shade: the shade has closed.
            val recent = now - shadeSeenAt < SHADE_TTL_MS
            shadeSeenAt = 0L
            if (recent) schedule(now)
        }
    }

    private fun schedule(now: Long) {
        handler.removeCallbacks(pending)
        val busyFor = lastRefresh + REFRESH_MS - now
        handler.postDelayed(pending, if (busyFor > 0) busyFor + SETTLE_MS else SETTLE_MS)
    }

    fun cancel() {
        handler.removeCallbacks(pending)
        shadeSeenAt = 0L
    }

    private fun loadTitles(): Set<String> {
        val found = mutableSetOf("Notification shade.", "Quick settings.")
        try {
            val res = service.packageManager.getResourcesForApplication(SYSTEMUI)
            for (name in listOf("accessibility_desc_notification_shade", "accessibility_desc_quick_settings")) {
                val id = res.getIdentifier(name, "string", SYSTEMUI)
                if (id != 0) found += res.getString(id)
            }
        } catch (_: Exception) {
        }
        return found
    }
}
