package com.lmqr.ha9_comp_service

data class HomeOverviewPlan(val width: Int, val height: Int) {
    val x = width / 2f
    val startY = height - 3f
    val endY = height * (1050f / 1648f)
    val moveMs = 350L
    val holdMs = 600L
}

/** Scope the workaround to the device/launcher path actually reproduced. */
fun homeOverviewPlan(packageName: String, className: String, navigationMode: Int,
    available: Boolean, width: Int, height: Int): HomeOverviewPlan? =
    if (available && packageName == "bitpit.launcher" &&
        className == "bitpit.launcher.ui.HomeActivity" && navigationMode == 2 &&
        width >= 320 && height > width) HomeOverviewPlan(width, height) else null
