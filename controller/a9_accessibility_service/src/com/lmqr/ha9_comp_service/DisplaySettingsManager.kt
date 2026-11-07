package com.lmqr.ha9_comp_service

import android.content.SharedPreferences
import android.graphics.Color
import android.widget.CompoundButton
import android.widget.TextView
import com.lmqr.ha9_comp_service.command_runners.CommandRunner

/**
 * Manages display settings like auto refresh and anti-shake
 */
class DisplaySettingsManager(
    private val sharedPreferences: SharedPreferences,
    private val commandRunner: CommandRunner
) {
    companion object {
        private const val PREF_AUTO_REFRESH = "auto_refresh_enabled"
        private const val PREF_ANTI_SHAKE = "anti_shake_enabled"
    }

    var isAutoRefreshEnabled: Boolean
        get() = sharedPreferences.getBoolean(PREF_AUTO_REFRESH, true)
        set(value) {
            sharedPreferences.edit().putBoolean(PREF_AUTO_REFRESH, value).apply()
            sendAutoRefresh()
        }

    // The driver's auto clean keeps about two CPU cores busy at real-time priority while the screen is on
    // (measured Nov 6 2026), which made Fast Keys' suggestions lag. So it pauses while the keyboard is up
    // and comes back (and cleans up) as soon as the keyboard closes.
    private var keyboardVisible = false

    fun setKeyboardVisible(visible: Boolean) {
        if (visible == keyboardVisible) return
        keyboardVisible = visible
        sendAutoRefresh()
    }

    // Same while Pie Pro's pie is open. Never paused for more than 5 s, in case the "closed" message is lost.
    private var pieOpen = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pieTimeout = Runnable { setPieOpen(false) }

    fun setPieOpen(open: Boolean) {
        handler.removeCallbacks(pieTimeout)
        if (open) handler.postDelayed(pieTimeout, 5_000L)
        if (open == pieOpen) return
        pieOpen = open
        sendAutoRefresh()
    }

    private fun sendAutoRefresh() {
        // Measured Nov 6 2026: pausing the clean while typing made typing no faster (letter ~40 ms either way) but left
        // more ghosting, so it stays on by default. Hidden pref pause_clean_for_keyboard=true pauses it (saves CPU/battery).
        val pauseForKeyboard = sharedPreferences.getBoolean("pause_clean_for_keyboard", false)
        val on = isAutoRefreshEnabled && !(keyboardVisible && pauseForKeyboard) && !pieOpen
        commandRunner.runCommands(arrayOf(if (on) "au_br1" else "au_br0"))
    }

    var isAntiShakeEnabled: Boolean
        get() = sharedPreferences.getBoolean(PREF_ANTI_SHAKE, true)
        set(value) {
            sharedPreferences.edit().putBoolean(PREF_ANTI_SHAKE, value).apply()
            commandRunner.runCommands(arrayOf(if (value) "an_fl1" else "an_fl0"))
        }

    /**
     * Apply all display settings (for use on boot or service start)
     */
    fun applySettings() {
        sendAutoRefresh()

        commandRunner.runCommands(arrayOf(if (isAntiShakeEnabled) "an_fl1" else "an_fl0"))
    }

    /**
     * Setup UI switches with current settings values and attach listeners
     */
    fun setupSwitches(autoRefresh: CompoundButton, antiShake: CompoundButton) {
        autoRefresh.isChecked = isAutoRefreshEnabled
        antiShake.isChecked = isAntiShakeEnabled
        
        if (autoRefresh is TextView) autoRefresh.setTextColor(Color.BLACK)
        if (antiShake is TextView) antiShake.setTextColor(Color.BLACK)
        
        autoRefresh.setOnCheckedChangeListener { _, isChecked ->
            isAutoRefreshEnabled = isChecked
        }
        
        antiShake.setOnCheckedChangeListener { _, isChecked ->
            isAntiShakeEnabled = isChecked
        }
    }
}
