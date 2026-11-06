package com.lmqr.ha9_comp_service

import android.content.SharedPreferences
import com.lmqr.ha9_comp_service.command_runners.CommandRunner
import com.lmqr.ha9_comp_service.command_runners.Commands

enum class RefreshMode(val command: String, val mode: Int) {
    CLEAR(Commands.SPEED_CLEAR, 0),
    BALANCED(Commands.SPEED_BALANCED, 1),
    SMOOTH(Commands.SPEED_SMOOTH, 2),
    SPEED(Commands.SPEED_FAST, 3),
    HYPER(Commands.SPEED_HYPER, 4);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.mode == value } ?: BALANCED
    }
}

class RefreshModeManager(
    private val sharedPreferences: SharedPreferences,
    private val commandRunner: CommandRunner,
) {
    companion object {
        const val FAST_KEYBOARD = "fast_keyboard_refresh"
        // 3 = Extreme: settles as fast as Hyper on page changes with less than half the ghosting.
        const val FAST_KEYBOARD_MODE = "fast_keyboard_mode"
    }
    private var keyboardVisible = false
    val isKeyboardVisible get() = keyboardVisible
    private var foregroundMode = RefreshMode.fromInt(defaultRefreshMode())
    var currentMode = foregroundMode
        private set
    init { applyMode() }
    private var currentClassifier = ""

    // Extreme by default for every app (owner, Nov 6 2026); per-app choices and the setting still override it.
    private fun defaultRefreshMode() = sharedPreferences.getString("refresh_setting", "1")
        ?.toIntOrNull()?.takeIf { it in 0..4 } ?: RefreshMode.SPEED.mode
    fun onAppChange(packageName: String) = packageName.run {
        if (this != currentClassifier) {
            currentClassifier = this
            val tempMode = RefreshMode.fromInt(
                sharedPreferences.getInt(
                    toSharedPreferencesKey(
                        !sharedPreferences.getBoolean(
                            "disable_perapprefresh",
                            false
                        )
                    ),
                    defaultRefreshMode()
                )
            )
            foregroundMode = tempMode
            updateEffectiveMode()
        }
    }

    fun changeMode(refreshMode: RefreshMode) {
        if (refreshMode != foregroundMode) {
            foregroundMode = refreshMode
            sharedPreferences.edit()
                .putInt(
                    currentClassifier.toSharedPreferencesKey(
                        !sharedPreferences.getBoolean(
                            "disable_perapprefresh",
                            false
                        )
                    ), refreshMode.mode
                ).apply()
            updateEffectiveMode()
        }
    }

    fun setKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
        updateEffectiveMode()
    }

    fun updateEffectiveMode() {
        val next = if (keyboardVisible && sharedPreferences.getBoolean(FAST_KEYBOARD, true))
            RefreshMode.fromInt(sharedPreferences.getString(FAST_KEYBOARD_MODE, "3")?.toIntOrNull() ?: 3)
        else foregroundMode
        if (next != currentMode) { currentMode = next; applyMode() }
    }

    // Camera, Oct 25 2026: the first frame drawn right after switching into Extreme was lost (78% of
    // the screen wrong; later frames fine). The service nudges a fresh frame when a fast mode starts.
    var onFastModeEntered: (() -> Unit)? = null
    private var lastApplied: RefreshMode? = null

    fun applyMode() {
        commandRunner.runCommands(
            arrayOf(currentMode.command)
        )
        val fast = currentMode == RefreshMode.SPEED || currentMode == RefreshMode.HYPER
        if (fast && lastApplied != currentMode) onFastModeEntered?.invoke()
        lastApplied = currentMode
    }

    /** Commands sent outside applyMode (a clean's HD switch) change the panel mode too. */
    fun forgetAppliedMode() { lastApplied = null }
}

private fun String.toSharedPreferencesKey(isPerAppEnabled: Boolean): String {
    if (isPerAppEnabled) {
        if (startsWith("package:"))
            return this
        return "package:$this"
    }
    return "package:None"
}
