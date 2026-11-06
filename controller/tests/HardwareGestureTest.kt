import com.lmqr.ha9_comp_service.ButtonGestureTimer
import com.lmqr.ha9_comp_service.HardwareGestureState

private class Clock : ButtonGestureTimer {
    var now = 1000L
    private val tasks = mutableMapOf<Int, Pair<Long, () -> Unit>>()
    override fun schedule(id: Int, at: Long, action: () -> Unit) { tasks[id] = at to action }
    override fun cancel(id: Int) { tasks.remove(id) }
    fun advance(to: Long) {
        require(to >= now)
        while (true) {
            val next = tasks.entries.filter { it.value.first <= to }.minByOrNull { it.value.first } ?: break
            now = next.value.first
            tasks.remove(next.key)
            next.value.second()
        }
        now = to
    }
}

private class Button {
    val clock = Clock()
    val events = mutableListOf<String>()
    val state = HardwareGestureState(clock, 300, 500,
        { events.add("short") }, { events.add("double") }, { events.add("long") })
    fun down(t: Long) { clock.advance(t); state.down(t) }
    fun up(t: Long) { clock.advance(t); state.up(t) }
    fun expect(vararg expected: String) { check(events == expected.toList()) { "Expected ${expected.toList()}, got $events" } }
}

fun main() {
    Button().apply {
        down(1000); up(1100); clock.advance(1399); expect()
        clock.advance(1400); expect("short"); clock.advance(3000); expect("short")
    }
    Button().apply {
        down(1000); down(1200); down(1450); clock.advance(1500); expect("long")
        down(1700); down(1900); up(2200); clock.advance(3000); expect("long")
        down(3100); up(3150); clock.advance(3500); expect("long", "short")
    }
    Button().apply {
        down(1000); up(1050); down(1200); expect()
        up(1250); clock.advance(2200); expect("double")
    }
    Button().apply {
        down(1000); up(1050); down(1200); down(1400); clock.advance(1700)
        up(1850); clock.advance(2400); expect("long")
    }
    Button().apply {
        up(1000); clock.advance(1500); expect()
        down(1600); state.cancel(); up(1900); clock.advance(2300); expect()
        down(2400); up(2450); state.cancel(); clock.advance(3000); expect()
    }
    val top = Button(); val bottom = Button()
    top.down(1000); bottom.down(1000); bottom.up(1050)
    top.clock.advance(1600); bottom.clock.advance(1600)
    top.expect("long"); bottom.expect("short")
    top.up(1700); top.clock.advance(2200); top.expect("long")
    println("PASS: short, held/repeated long, double, second-tap hold, cancellation, stray release, and independent buttons")
}
