package com.lmqr.ha9_comp_service

import android.content.SharedPreferences
import android.widget.SeekBar
import com.lmqr.ha9_comp_service.command_runners.CommandRunner

/**
 * Manages brightness settings and their persistence
 */
class BrightnessManager(
    private val sharedPreferences: SharedPreferences,
    private val commandRunner: CommandRunner
) {
    companion object {
        private const val PREF_COLD_BRIGHTNESS = "cold_brightness_value"
        private const val PREF_WARM_BRIGHTNESS = "warm_brightness_value"
        private const val PREF_ENABLED = "frontlights_enabled"
        private const val PREF_WARM_ENABLED = "warm_frontlight_enabled"
        // The cool/warm mix last set in the floating menu. The shade slider scales it, so
        // dragging the shade to the bottom and back up keeps the owner's colour.
        private const val PREF_MIX_COLD = "frontlight_mix_cold"
        private const val PREF_MIX_WARM = "frontlight_mix_warm"

        // Android's slider curve (BrightnessUtils, HLG): slider position <-> linear brightness.
        private const val R = 0.5
        private const val A = 0.17883277
        private const val B = 0.28466892
        private const val C = 0.55991073

        /** Linear brightness 0..1 -> slider position 0..1, as SystemUI draws it. */
        fun linearToSlider(linear: Double): Double {
            val v = linear.coerceIn(0.0, 1.0) * 12
            val g = if (v <= 1) kotlin.math.sqrt(v) * R else A * kotlin.math.ln(v - B) + C
            return g.coerceIn(0.0, 1.0)
        }

        /** Slider position 0..1 -> linear brightness 0..1. */
        fun sliderToLinear(slider: Double): Double {
            val g = slider.coerceIn(0.0, 1.0)
            val v = if (g <= R) (g / R) * (g / R) else kotlin.math.exp((g - C) / A) + B
            return (v.coerceIn(0.0, 12.0) / 12)
        }

        /** Settings.System.SCREEN_BRIGHTNESS (1..255) -> front light peak 0..254. */
        fun settingToPeak(setting: Int): Int =
            kotlin.math.round(linearToSlider((setting - 1) / 254.0) * 254).toInt().coerceIn(0, 254)

        /** Front light peak 0..254 -> Settings.System.SCREEN_BRIGHTNESS (1..255). A lit level never maps to 1 (off). */
        fun peakToSetting(peak: Int): Int {
            if (peak <= 0) return 1
            val v = kotlin.math.round(sliderToLinear(peak.coerceAtMost(254) / 254.0) * 254).toInt() + 1
            return v.coerceIn(2, 255)
        }
    }

    /** Called when the owner lets go of a floating-menu slider (so the shade can follow). */
    var onMenuLevelChanged: (() -> Unit)? = null
    private var coldBar: SeekBar? = null
    private var warmBar: SeekBar? = null

    /** Overall level: the brighter channel. */
    val peak: Int get() = maxOf(coldBrightness, warmBrightness)

    private fun rememberMix() {
        if (peak > 0) sharedPreferences.edit().putInt(PREF_MIX_COLD, coldBrightness)
            .putInt(PREF_MIX_WARM, warmBrightness).apply()
    }

    /**
     * Shade slider: scale the remembered cool/warm mix so its brighter channel = [newPeak].
     * 0 turns the light off without forgetting the mix. Does not apply; the caller does.
     */
    fun setPeakKeepingMix(newPeak: Int) {
        val p = newPeak.coerceIn(0, 254)
        var mc = sharedPreferences.getInt(PREF_MIX_COLD, -1)
        var mw = sharedPreferences.getInt(PREF_MIX_WARM, -1)
        if (mc < 0 || mw < 0 || maxOf(mc, mw) <= 0) {
            if (peak > 0) { mc = coldBrightness; mw = warmBrightness } else { mc = 254; mw = 254 }
            sharedPreferences.edit().putInt(PREF_MIX_COLD, mc).putInt(PREF_MIX_WARM, mw).apply()
        }
        val m = maxOf(mc, mw).toDouble()
        coldBrightness = kotlin.math.round(p * mc / m).toInt()
        warmBrightness = kotlin.math.round(p * mw / m).toInt()
        if (p > 0) sharedPreferences.edit().putBoolean(PREF_ENABLED, true).apply()
        coldBar?.progress = coldBrightness
        warmBar?.progress = warmBrightness
    }

    var coldBrightness: Int
        get() = sharedPreferences.getInt(PREF_COLD_BRIGHTNESS, 64).coerceIn(0, 254)
        set(value) {
            sharedPreferences.edit().putInt(PREF_COLD_BRIGHTNESS, value.coerceIn(0, 254)).apply()
        }

    var warmBrightness: Int
        get() = sharedPreferences.getInt(PREF_WARM_BRIGHTNESS, 64).coerceIn(0, 254)
        set(value) {
            sharedPreferences.edit().putInt(PREF_WARM_BRIGHTNESS, value.coerceIn(0, 254)).apply()
        }

    /**
     * Apply current brightness settings to the device
     */
    fun applyBrightness() {
        val enabled = sharedPreferences.getBoolean(PREF_ENABLED, true)
        val warmEnabled = sharedPreferences.getBoolean(PREF_WARM_ENABLED, true)
        val cold = if (enabled) coldBrightness else 0
        val warm = if (enabled && warmEnabled) warmBrightness else 0
        commandRunner.runCommands(arrayOf("br_co$cold", "br_wm$warm"))
    }

    fun toggleBrightness() {
        sharedPreferences.edit().putBoolean(PREF_ENABLED,
            !sharedPreferences.getBoolean(PREF_ENABLED, true)).apply()
        applyBrightness()
    }

    fun toggleWarmLight() {
        sharedPreferences.edit().putBoolean(PREF_WARM_ENABLED,
            !sharedPreferences.getBoolean(PREF_WARM_ENABLED, true)).apply()
        applyBrightness()
    }

    /**
     * Update both brightness values and apply them
     */
    fun setBrightness(coldValue: Int, warmValue: Int) {
        coldBrightness = coldValue
        warmBrightness = warmValue
        rememberMix()
        sharedPreferences.edit().putBoolean(PREF_ENABLED, true)
            .putBoolean(PREF_WARM_ENABLED, true).apply()
        applyBrightness()
    }

    /**
     * Turn off both backlights
     */
    fun turnOffBrightness() {
        commandRunner.runCommands(arrayOf("br_co0", "br_wm0"))
    }

    /**
     * Configure seekbars with current brightness values and listeners
     */
    fun setupSeekBars(lightSeekbar: SeekBar, lightWarmSeekbar: SeekBar) {
        coldBar = lightSeekbar
        warmBar = lightWarmSeekbar
        lightSeekbar.min = 0
        lightSeekbar.max = 254
        lightSeekbar.progress = coldBrightness
        lightSeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        coldBrightness = progress
                        sharedPreferences.edit().putBoolean(PREF_ENABLED, true).apply()
                        rememberMix()
                        applyBrightness()
                    }
                }
                
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    coldBrightness = seekBar?.progress ?: 0
                    onMenuLevelChanged?.invoke()
                }
            }
        )

        lightWarmSeekbar.min = 0
        lightWarmSeekbar.max = 254
        lightWarmSeekbar.progress = warmBrightness
        lightWarmSeekbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        warmBrightness = progress
                        sharedPreferences.edit().putBoolean(PREF_ENABLED, true)
                            .putBoolean(PREF_WARM_ENABLED, true).apply()
                        rememberMix()
                        applyBrightness()
                    }
                }
                
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    warmBrightness = seekBar?.progress ?: 0
                    onMenuLevelChanged?.invoke()
                }
            }
        )
    }
}
