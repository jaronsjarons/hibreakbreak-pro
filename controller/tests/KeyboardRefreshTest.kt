import android.content.SharedPreferences
import com.lmqr.ha9_comp_service.RefreshMode
import com.lmqr.ha9_comp_service.RefreshModeManager
import com.lmqr.ha9_comp_service.command_runners.CommandRunner
import java.lang.reflect.Proxy

fun main() {
    val stored = mutableMapOf<String,Any>("refresh_setting" to "1",
        "package:reader" to 0, "package:com.android.settings" to 4)
    lateinit var editor: SharedPreferences.Editor
    editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
        when(method.name) {
            "putInt", "putBoolean" -> { stored[args!![0] as String]=args[1]; editor }
            "apply" -> null
            else -> error("Unexpected edit: ${method.name}")
        }
    } as SharedPreferences.Editor
    val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when(method.name) {
            "getInt", "getString", "getBoolean" -> stored[args!![0]] ?: args[1]
            "edit" -> editor
            else -> error("Unexpected read: ${method.name}")
        }
    } as SharedPreferences
    val commands = mutableListOf<String>()
    val runner = object: CommandRunner {
        override fun runCommands(cmds: Array<String>) { commands.addAll(cmds) }
        override fun onDestroy() {}
    }
    val manager=RefreshModeManager(prefs,runner)
    fun expect(mode: RefreshMode) {
        check(manager.currentMode==mode && commands.last()==mode.command)
    }
    manager.onAppChange("reader"); expect(RefreshMode.CLEAR)
    val original=stored.toMap()
    manager.setKeyboardVisible(true); expect(RefreshMode.HYPER)
    val count=commands.size
    repeat(100) { manager.setKeyboardVisible(true) }
    check(commands.size==count) { "Duplicate keyboard signals generated display writes" }
    manager.onAppChange("browser"); expect(RefreshMode.HYPER)
    manager.setKeyboardVisible(false); expect(RefreshMode.BALANCED)
    manager.onAppChange("reader"); expect(RefreshMode.CLEAR)
    check(stored==original) { "Temporary keyboard override overwrote app profiles" }
    manager.onAppChange("com.android.settings"); expect(RefreshMode.HYPER)
    manager.setKeyboardVisible(true); manager.setKeyboardVisible(false); expect(RefreshMode.HYPER)
    manager.onAppChange("reader"); manager.setKeyboardVisible(true)
    stored[RefreshModeManager.FAST_KEYBOARD]=false; manager.updateEffectiveMode(); expect(RefreshMode.CLEAR)
    stored[RefreshModeManager.FAST_KEYBOARD]=true; manager.updateEffectiveMode(); expect(RefreshMode.HYPER)
    manager.changeMode(RefreshMode.SPEED); expect(RefreshMode.HYPER)
    manager.setKeyboardVisible(false); expect(RefreshMode.SPEED)
    check(stored["package:reader"]==3)
    val restarted=RefreshModeManager(prefs,runner); restarted.onAppChange("reader")
    check(restarted.currentMode==RefreshMode.SPEED)
    println("PASS: keyboard Hyper, foreground changes, profile restoration/persistence, Settings Hyper, preference toggle, and duplicate-event suppression")
}
