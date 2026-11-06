package com.lmqr.ha9_comp_service

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostic (Oct 25 2026): when and why the controller drew a black flash, plus screen on/off,
 * to tell its flashes apart from the driver's own. Time, cause and foreground package only; kept
 * on the phone (files/flash-log.txt), last 300 lines. Read with: su -c cat .../files/flash-log.txt
 */
object FlashLog {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun add(context: Context, what: String, pkg: String = "") {
        try {
            val f = File(context.filesDir, "flash-log.txt")
            val lines = if (f.exists()) f.readLines().takeLast(MAX - 1) else emptyList()
            f.writeText((lines + "${fmt.format(Date())} $what $pkg".trimEnd()).joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }
}
