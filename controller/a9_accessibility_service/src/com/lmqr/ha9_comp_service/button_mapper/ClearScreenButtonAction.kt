package com.lmqr.ha9_comp_service.button_mapper
import android.content.Context
import com.lmqr.ha9_comp_service.A9AccessibilityService
import com.lmqr.ha9_comp_service.RefreshModeManager
import com.lmqr.ha9_comp_service.command_runners.CommandRunner
class ClearScreenButtonAction(commandRunner: CommandRunner, refreshModeManager: RefreshModeManager) : ButtonAction {
    override fun execute(context: Context) { (context as? A9AccessibilityService)?.clearScreen("button") }
}
