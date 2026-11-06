import android.content.SharedPreferences
import com.lmqr.ha9_comp_service.BrightnessManager
import com.lmqr.ha9_comp_service.command_runners.CommandRunner
import java.lang.reflect.Proxy

fun main() {
    val stored = mutableMapOf<String, Any>()
    lateinit var editor: SharedPreferences.Editor
    editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
        when (method.name) {
            "putInt", "putBoolean" -> { stored[args!![0] as String] = args[1]; editor }
            "apply" -> null
            "commit" -> true
            else -> error("Unexpected preference edit: ${method.name}")
        }
    } as SharedPreferences.Editor
    val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getInt", "getBoolean" -> stored[args!![0]] ?: args[1]
            "edit" -> editor
            else -> error("Unexpected preference read: ${method.name}")
        }
    } as SharedPreferences
    var last = emptyList<String>()
    val runner = object : CommandRunner {
        override fun runCommands(cmds: Array<String>) { last = cmds.toList() }
        override fun onDestroy() {}
    }
    fun expect(cold: Int, warm: Int) {
        check(last == listOf("br_co$cold", "br_wm$warm")) { "Unexpected output: $last" }
    }
    val lights = BrightnessManager(prefs, runner)
    lights.setBrightness(120, 80); expect(120,80)
    lights.toggleBrightness(); expect(0,0)
    lights.turnOffBrightness(); expect(0,0)
    lights.applyBrightness(); expect(0,0)
    lights.toggleBrightness(); expect(120,80)
    lights.toggleWarmLight(); expect(120,0)
    val beforeSleep = stored.toMap()
    lights.turnOffBrightness(); expect(0,0)
    check(stored == beforeSleep) { "Sleep overwrote the user's brightness settings" }
    lights.applyBrightness(); expect(120,0)
    val restarted = BrightnessManager(prefs, runner)
    restarted.applyBrightness(); expect(120,0)
    restarted.toggleWarmLight(); expect(120,80)
    restarted.setBrightness(-10, 9999); expect(0,254)
    // Shade slider (Nov 6 2026): scales the cool/warm mix, bottom = off, mix survives off.
    restarted.setBrightness(0, 127); expect(0,127)
    restarted.setPeakKeepingMix(254); restarted.applyBrightness(); expect(0,254)
    restarted.setPeakKeepingMix(0); restarted.applyBrightness(); expect(0,0)
    restarted.setPeakKeepingMix(100); restarted.applyBrightness(); expect(0,100)
    restarted.setBrightness(200, 100)
    restarted.setPeakKeepingMix(100); restarted.applyBrightness(); expect(100,50)
    check(BrightnessManager.settingToPeak(1) == 0) { "Shade bottom must be off" }
    check(BrightnessManager.settingToPeak(255) == 254) { "Shade top must be full" }
    for (p in 0..254) {
        val back = BrightnessManager.settingToPeak(BrightnessManager.peakToSetting(p))
        // Android's int setting is coarse at the bottom (2 = ~11% of the slider).
        check(if (p == 0) back == 0 else back > 0 && kotlin.math.abs(back - p) <= (if (p < 40) 27 else 8)) { "Round trip $p -> $back" }
    }
    var prev = -1
    for (v in 1..255) { val p = BrightnessManager.settingToPeak(v); check(p >= prev) { "Not monotonic at $v" }; prev = p }
    println("shade 7 -> ${BrightnessManager.settingToPeak(7)}, peak 127 -> setting ${BrightnessManager.peakToSetting(127)}")
    println("PASS: independent channels, off/on restore, warm toggle, sleep preservation, restart persistence and bounds")
}
