import com.lmqr.ha9_comp_service.homeOverviewPlan

fun main() {
    fun plan(pkg: String = "bitpit.launcher", cls: String = "bitpit.launcher.ui.HomeActivity",
        nav: Int = 2, available: Boolean = true, width: Int = 824, height: Int = 1648) =
        homeOverviewPlan(pkg, cls, nav, available, width, height)
    check(plan()?.let { it.x == 412f && it.startY == 1645f && it.endY == 1050f &&
        it.moveMs == 350L && it.holdMs == 600L } == true)
    check(plan(available = false) == null) // Locked, asleep or disabled Pie.
    check(plan(pkg = "com.android.settings") == null)
    check(plan(cls = "bitpit.launcher.ui.SettingsActivity") == null)
    check(plan(pkg = "", cls = "") == null) // No confirmed foreground identity.
    check(plan(nav = 0) == null && plan(nav = 1) == null && plan(nav = -1) == null)
    check(plan(width = 1648, height = 824) == null)
    check(plan(width = 824, height = 824) == null)
    check(plan(width = 0) == null && plan(height = 0) == null)
    check(plan(width = 1080, height = 2160)?.let { it.x == 540f && it.endY < it.startY } == true)
    println("PASS: exact tested gesture, Home-only scope, unavailable/unknown/app/rotation/navigation guards")
}
