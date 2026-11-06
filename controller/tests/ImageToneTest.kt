import android.content.SharedPreferences
import com.lmqr.ha9_comp_service.ImageToneManager
import com.lmqr.ha9_comp_service.imageToneMatrix
import java.lang.reflect.Proxy

fun main() {
    val stored = mutableMapOf<String, Any>()
    lateinit var editor: SharedPreferences.Editor
    editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
        when (method.name) {
            "putInt", "putBoolean" -> { stored[args!![0] as String] = args[1]; editor }
            "remove" -> { stored.remove(args!![0] as String); editor }
            "apply" -> null
            "commit" -> true
            else -> error("Unexpected preference edit: ${method.name}")
        }
    } as SharedPreferences.Editor
    val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getInt", "getBoolean" -> stored[args!![0]] ?: args[1]
            "contains" -> stored.containsKey(args!![0])
            "edit" -> editor
            else -> error("Unexpected preference read: ${method.name}")
        }
    } as SharedPreferences
    var last = emptyList<String>()
    val image = ImageToneManager(prefs) { brightness, contrast -> last=listOf("im_co$contrast","im_br$brightness") }
    stored["image:reader:contrast"] = 45
    stored["image:reader:brightness"] = 25
    stored["image:browser:contrast"] = 12
    stored["image:browser:brightness"] = 70
    image.onAppChange("reader"); check(last == listOf("im_co45","im_br25"))
    image.onAppChange("browser"); check(last == listOf("im_co12","im_br70"))
    val before = stored.toMap()
    image.resetForSleep(); check(last == listOf("im_co0","im_br0")); check(stored == before)
    image.apply(); check(last == listOf("im_co12","im_br70"))
    image.onAppChange("reader"); check(last == listOf("im_co45","im_br25"))
    image.onAppChange("new-app"); check(last == listOf("im_co0","im_br0"))
    // A global change reaches unconfigured apps but must not clobber existing profiles.
    stored[ImageToneManager.DEFAULT_CONTRAST] = 10
    image.apply(); check(last == listOf("im_co10","im_br0"))
    image.onAppChange("reader"); check(last == listOf("im_co45","im_br25"))
    stored["image:reader:contrast"] = 0
    image.apply(); check(last == listOf("im_co0","im_br25")) // Explicit neutral is an override.
    stored.remove("image:reader:contrast")
    image.apply(); check(last == listOf("im_co10","im_br25")) // Return to inheritance.
    stored[ImageToneManager.DEFAULT_CONTRAST] = 20
    image.apply(); check(last == listOf("im_co20","im_br25"))
    val withDefault = stored.toMap()
    image.resetForSleep(); check(last == listOf("im_co0","im_br0")); check(stored == withDefault)
    image.apply(); check(last == listOf("im_co20","im_br25"))
    val restarted = ImageToneManager(prefs) { brightness, contrast -> last=listOf("im_co$contrast","im_br$brightness") }
    restarted.onAppChange("reader"); check(last == listOf("im_co20","im_br25"))
    image.onAppChange("new-app")
    stored["image:new-app:contrast"] = 999; stored["image:new-app:brightness"] = -10
    image.apply(); check(last == listOf("im_co99","im_br0"))
    check(last.none { it.startsWith("br_") })
    val identity = imageToneMatrix(0,0)
    check(identity.contentEquals(floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)))
    val strong = imageToneMatrix(100,99)
    check(strong.all { it.isFinite() }); check(strong[3]==0f && strong[7]==0f && strong[11]==0f && strong[15]==1f)
    check(imageToneMatrix(-10,999).contentEquals(imageToneMatrix(0,99)))
    check(imageToneMatrix(100,0)[12] > 0f)
    println("PASS: global inheritance, explicit neutral override, return to default, persistence, sleep/wake, app brightness preservation, matrix bounds, and no light commands")
}
