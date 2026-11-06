package com.lmqr.ha9_comp_service

/** A separate instance belongs to each physical button. Times use uptime. */
interface ButtonGestureTimer {
    fun schedule(id: Int, at: Long, action: () -> Unit)
    fun cancel(id: Int)
}

class HardwareGestureState(
    private val timer: ButtonGestureTimer,
    private val doubleDelay: Long,
    private val longDelay: Long,
    private val shortPress: () -> Unit,
    private val doublePress: () -> Unit,
    private val longPress: () -> Unit,
) {
    private var held = false
    private var longFired = false
    private var secondTap = false
    private var waitingShort = false
    private var lastUp = 0L

    fun down(time: Long) {
        if (held) return // Linux/Android key repeats must not start another gesture.
        secondTap = waitingShort && time - lastUp in 0..doubleDelay
        if (waitingShort) {
            timer.cancel(SHORT)
            waitingShort = false
            if (!secondTap) shortPress()
        }
        held = true
        longFired = false
        timer.schedule(LONG, time + longDelay) {
            if (held) {
                longFired = true
                secondTap = false
                longPress()
            }
        }
    }

    fun up(time: Long) {
        if (!held) return
        held = false
        timer.cancel(LONG)
        if (longFired) {
            longFired = false
            secondTap = false
        } else if (secondTap) {
            secondTap = false
            doublePress() // Only a completed second short press is a double press.
        } else {
            waitingShort = true
            lastUp = time
            timer.schedule(SHORT, time + doubleDelay) {
                if (waitingShort) {
                    waitingShort = false
                    shortPress()
                }
            }
        }
    }

    fun cancel() {
        timer.cancel(SHORT)
        timer.cancel(LONG)
        held = false
        longFired = false
        secondTap = false
        waitingShort = false
    }

    private companion object { const val SHORT = 1; const val LONG = 2 }
}
