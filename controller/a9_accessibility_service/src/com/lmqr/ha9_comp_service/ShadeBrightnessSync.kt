package com.lmqr.ha9_comp_service

import SystemSettingsManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * The pull-down shade's brightness slider drives the e-ink front light (Nov 6 2026).
 * There is no LCD backlight, so SCREEN_BRIGHTNESS is otherwise unused. The slider position
 * (Android's curve) scales the floating menu's cool/warm mix; the bottom = light off.
 * The floating menu sliders move the shade slider to match when the owner lets go.
 * Screen off / doze is untouched: changes are only applied while the screen is on.
 */
class ShadeBrightnessSync(
    private val context: Context,
    private val handler: Handler,
    private val lights: BrightnessManager,
    private val isScreenOn: () -> Boolean,
) {
    private val resolver = context.contentResolver
    private val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = onShadeChanged()
    }

    fun start() {
        if (registered) return
        resolver.registerContentObserver(uri, false, observer)
        registered = true
        lights.onMenuLevelChanged = { pushLevelToShade() }
    }

    fun stop() {
        if (registered) resolver.unregisterContentObserver(observer)
        registered = false
        lights.onMenuLevelChanged = null
    }

    private fun onShadeChanged() {
        val value = SystemSettingsManager.getBrightnessFromSetting(context)
        // Our own writes (menu sync, night-light ±1 nudge) echo back here: skip them once.
        if (value == SystemSettingsManager.lastSelfWrite &&
            SystemClock.uptimeMillis() - SystemSettingsManager.lastSelfWriteAt < 3000) {
            SystemSettingsManager.lastSelfWrite = -1
            return
        }
        if (!isScreenOn()) return
        val peak = BrightnessManager.settingToPeak(value)
        if (peak == lights.peak) return
        Log.d("HiBreakLight", "shade brightness $value -> front light peak $peak")
        lights.setPeakKeepingMix(peak)
        lights.applyBrightness()
    }

    /** Move the shade slider to the menu's current level, if it isn't there already. */
    fun pushLevelToShade() {
        val current = SystemSettingsManager.getBrightnessFromSetting(context)
        if (BrightnessManager.settingToPeak(current) == lights.peak) return
        val target = BrightnessManager.peakToSetting(lights.peak)
        if (target != current) SystemSettingsManager.setBrightnessSetting(context, target)
    }
}
