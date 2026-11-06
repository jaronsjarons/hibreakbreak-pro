package com.lmqr.ha9_comp_service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat

/** A bounded stock compositor cadence setting. This does not measure panel FPS. */
fun PreferenceFragmentCompat.bindRenderRatePreference() {
    val pref = findPreference<ListPreference>("render_rate") ?: return
    val rates = setOf("0", "15", "30", "60")
    if (pref.value !in rates) pref.value = "0"
    fun updateSummary() {
        val selected = pref.value.toIntOrNull() ?: 0
        val desired = if (selected == 0) 60 else selected
        @Suppress("DEPRECATION")
        val current = requireActivity().windowManager.defaultDisplay.refreshRate
        pref.summary = "${pref.entry}" + if (kotlin.math.abs(current - desired) > 1f)
            " · restart to apply" else ""
    }
    updateSummary()
    // The driver preference is authoritative, including after a process restart.
    pref.isEnabled = false
    Thread({
        val requested = try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress("a9_eink_socket", LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 1500
                check(socket.peerCredentials.uid == 0) { "Unexpected control service" }
                socket.outputStream.write("get_requested_fps\n".toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                val reply = socket.inputStream.bufferedReader(Charsets.US_ASCII).readLine()
                check(reply != null && reply.startsWith("VALUE ")) { "Frame-rate read failed" }
                val value = reply.removePrefix("VALUE ").let { if (it == "<unset>") "0" else it }
                check(value in rates) { "Unknown driver frame rate" }
                value
            }
        } catch (e: Exception) {
            Log.e("HiBreakControls", "Could not read rendering limit", e)
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (!isAdded) return@post
            if (requested == null) {
                pref.summary = "Unavailable. Reopen settings to try again."
            } else {
                pref.value = requested
                pref.isEnabled = true
                updateSummary()
            }
        }
    }, "HiBreakReadRenderRate").start()
    pref.setOnPreferenceChangeListener { _, newValue ->
        val value = newValue as? String
        if (value !in rates) return@setOnPreferenceChangeListener false
        pref.isEnabled = false
        Thread({
            val succeeded = try {
                LocalSocket().use { socket ->
                    socket.connect(LocalSocketAddress("a9_eink_socket", LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 1500
                    check(socket.peerCredentials.uid == 0) { "Unexpected control service" }
                    socket.outputStream.write("rr_fps$value\n".toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    check(socket.inputStream.bufferedReader(Charsets.US_ASCII).readLine() == "OK") {
                        "Driver rejected cadence setting"
                    }
                }
                true
            } catch (e: Exception) {
                Log.e("HiBreakControls", "Rendering limit was not saved", e)
                false
            }
            Handler(Looper.getMainLooper()).post {
                // Persist only an acknowledged setting, even if the user left this screen.
                if (succeeded) pref.value = value
                if (!isAdded) return@post
                pref.isEnabled = true
                updateSummary()
                Toast.makeText(requireContext(), if (succeeded)
                    "Saved. Restart the phone to apply." else
                    "Could not save the frame rate. Try again.", Toast.LENGTH_LONG).show()
            }
        }, "HiBreakRenderRate").start()
        false // The asynchronous acknowledgement commits the preference.
    }
}
