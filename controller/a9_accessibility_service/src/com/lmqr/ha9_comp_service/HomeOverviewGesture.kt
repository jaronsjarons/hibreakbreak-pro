package com.lmqr.ha9_comp_service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/** Native bottom-edge overview gesture for Niagara Home. No content access or shell. */
class HomeOverviewGesture(private val service: AccessibilityService,
    private val available: () -> Boolean, private val unavailable: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var generation = 0
    private var busy = false

    @Suppress("DEPRECATION")
    private fun foreground(): ComponentName? {
        // This is an existing system-UID controller with GET_TASKS already granted.
        // Ordinary apps receive filtered task lists, which must NOT be used to
        // infer that Home is in front. Read one component only, on user request.
        if (Process.myUid() != Process.SYSTEM_UID ||
            service.checkSelfPermission("android.permission.GET_TASKS") != PackageManager.PERMISSION_GRANTED) return null
        return try { service.getSystemService(ActivityManager::class.java)
            .getRunningTasks(1).firstOrNull()?.topActivity }
        catch (failure: RuntimeException) { Log.w("HiBreakOverview", "Foreground task unavailable", failure); null }
    }

    private fun navigationMode() = Settings.Secure.getInt(service.contentResolver, "navigation_mode", -1)
    private fun plan(): HomeOverviewPlan? {
        val b = wm.currentWindowMetrics.bounds
        val top = foreground() ?: return null
        return homeOverviewPlan(top.packageName, top.className, navigationMode(), available(), b.width(), b.height())
    }

    private fun isOverviewContext(top: ComponentName?) = top != null &&
        ((top.packageName == "bitpit.launcher" && top.className == "bitpit.launcher.ui.HomeActivity") ||
        (top.packageName == "com.google.android.apps.nexuslauncher" && top.className == "com.android.quickstep.RecentsActivity"))

    private fun mayHold(token: Int, plan: HomeOverviewPlan): Boolean {
        val b = wm.currentWindowMetrics.bounds
        return token == generation && available() && navigationMode() == 2 &&
            b.width() == plan.width && b.height() == plan.height && isOverviewContext(foreground())
    }

    /** True means this request is handled here; false leaves the ordinary action intact. */
    fun request(): Boolean {
        val requested = plan() ?: return false
        if (busy) return true
        busy = true
        val token = ++generation
        // Let the owner's Pie ACTION_UP finish before injecting the new gesture.
        handler.post {
            if (token != generation || plan() != requested) { busy = false; return@post }
            val move = GestureDescription.StrokeDescription(Path().apply {
                moveTo(requested.x, requested.startY)
                lineTo(requested.x, requested.endY)
            }, 0, requested.moveMs, true)
            Log.i("HiBreakOverview", "Starting native Home overview")
            val accepted = dispatch(move, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    // Always end our retained pointer, even if sleep/rotation/app change
                    // invalidated the request. A cancelled request gets no hold/retry.
                    val hold = mayHold(token, requested)
                    val release = move.continueStroke(Path().apply {
                        moveTo(requested.x, requested.endY)
                    }, 0, if (hold) requested.holdMs else 1, false)
                    if (!dispatch(release, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) {
                            busy = false
                            Log.i("HiBreakOverview", if (hold) "Native overview gesture completed" else "Cancelled request released")
                        }
                        override fun onCancelled(gestureDescription: GestureDescription) { finishCancelled() }
                    })) failed(token)
                }
                override fun onCancelled(gestureDescription: GestureDescription) { finishCancelled() }
            })
            if (!accepted) failed(token)
        }
        return true
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription,
        callback: AccessibilityService.GestureResultCallback): Boolean = try {
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, handler)
    } catch (failure: RuntimeException) {
        Log.e("HiBreakOverview", "Native overview dispatch failed", failure)
        false
    }

    private fun failed(token: Int) {
        busy = false
        Log.w("HiBreakOverview", "Native overview was not accepted")
        if (token == generation && available()) unavailable()
    }

    private fun finishCancelled() {
        busy = false
        Log.i("HiBreakOverview", "Native overview cancelled; no retry")
    }

    fun cancel() { generation++ }
}
