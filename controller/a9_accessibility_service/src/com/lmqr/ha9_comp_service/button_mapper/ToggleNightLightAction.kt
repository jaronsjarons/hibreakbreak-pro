package com.lmqr.ha9_comp_service.button_mapper

import android.content.Context
import com.lmqr.ha9_comp_service.BrightnessManager

class ToggleNightLightAction(private val brightnessManager: BrightnessManager) : ButtonAction {
    override fun execute(context: Context) {
        brightnessManager.toggleWarmLight()
    }
}
