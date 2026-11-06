package com.lmqr.ha9_comp_service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Observes system IME insets only; never reads keyboard text or accessibility nodes. */
class KeyboardModeObserver(private val service: AccessibilityService,
    private val onVisibilityChanged: (Boolean) -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var visible = false

    fun start() {
        if (view != null) { view?.requestApplyInsets(); return }
        val observer = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnApplyWindowInsetsListener { _, insets ->
                update(insets.isVisible(WindowInsets.Type.ime()))
                insets
            }
        }
        val params = WindowManager.LayoutParams(1,1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setFitInsetsTypes(0)
            title = "HiBreak keyboard mode observer"
        }
        try {
            wm.addView(observer,params)
            view = observer
            observer.requestApplyInsets()
        } catch (e: Exception) { Log.e("HiBreakKeyboard","Could not observe keyboard visibility",e) }
    }

    fun stop() {
        view?.let { if(it.isAttachedToWindow) wm.removeViewImmediate(it) }
        view = null
        update(false)
    }

    private fun update(next: Boolean) {
        if (next == visible) return
        visible = next
        Log.i("HiBreakKeyboard","visible=$next")
        onVisibilityChanged(next)
    }
}
