package com.lmqr.ha9_comp_service.command_runners

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serialized, bounded IPC; no socket waits on the accessibility/UI thread. */
class UnixSocketCommandRunner : CommandRunner {
    private val queue = LinkedBlockingDeque<Runnable>(64)
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        queue,
        { runnable -> Thread(runnable, "HiBreakControls").apply { isDaemon = true } })
    private val submissionLock = Any()
    @Volatile private var closed = false
    @Volatile private var displayAwake = true
    @Volatile private var activeSocket: LocalSocket? = null
    init { worker.prestartCoreThread() }

    private inner class Batch(val commands: Array<String>, val key: String?) : Runnable {
        override fun run() { if (!closed) sendBatch(commands) }
    }
    private fun batchKey(commands: Array<String>): String? = when {
        commands.all { it.startsWith("br_co") || it.startsWith("br_wm") } -> "lights"
        commands.size == 1 && commands[0] in setOf("b", "c", "s", "p", "h") -> "mode"
        commands.size == 1 && commands[0].startsWith("au_br") -> "auto"
        commands.size == 1 && commands[0].startsWith("an_fl") -> "flicker"
        commands.all { it.startsWith("im_br") || it.startsWith("im_co") } -> "image"
        else -> null
    }

    override fun setDisplayAwake(awake: Boolean) {
        synchronized(submissionLock) {
            displayAwake = awake
            if (!awake && !closed) {
                queue.removeIf { it is Batch && it.key == "lights" }
                // Sleep bypasses slider updates and other queued work. In-flight
                // writes remain bounded and recheck displayAwake per command.
                if (!queue.offerFirst(Batch(arrayOf("br_co0", "br_wm0"), "lights"))) {
                    queue.pollLast()
                    queue.offerFirst(Batch(arrayOf("br_co0", "br_wm0"), "lights"))
                }
            }
        }
    }

    override fun runCommands(cmds: Array<String>) {
        if (closed || cmds.isEmpty()) return
        val batch = cmds.copyOf()
        if (batch.size > 32 || batch.any { it.isEmpty() || it.length >= 64 || it.any { c -> c.code !in 32..126 } }) {
            Log.e("HiBreakControls", "Rejected malformed command batch")
            return
        }
        try {
            synchronized(submissionLock) {
                if (closed) return
                val key = batchKey(batch)
                if (key == "lights" && !displayAwake && batch.any { it != "br_co0" && it != "br_wm0" }) return
                if (key != null) queue.removeIf { it is Batch && it.key == key }
                val accepted = if (key == "lights" && !displayAwake)
                    queue.offerFirst(Batch(batch, key)) else queue.offerLast(Batch(batch, key))
                if (!accepted) throw RejectedExecutionException()
            }
        } catch (_: RejectedExecutionException) {
            Log.e("HiBreakControls", "Control queue unavailable; command was not sent")
        }
    }

    private fun sendBatch(commands: Array<String>) {
        try {
            LocalSocket().use { socket ->
                activeSocket = socket
                if (closed) return
                socket.connect(LocalSocketAddress("a9_eink_socket", LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 500
                if (socket.peerCredentials.uid != 0) throw IOException("Control server is not root")
                val reader = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                for (command in commands) {
                    if (closed) return
                    if (!displayAwake && (command.startsWith("br_co") || command.startsWith("br_wm"))
                        && command != "br_co0" && command != "br_wm0") continue
                    socket.outputStream.write((command + "\n").toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    if (reader.readLine() != "OK") throw IOException("Control rejected or connection closed")
                }
            }
        } catch (e: IOException) {
            Log.e("HiBreakControls", "Hardware control failed", e)
        } finally {
            activeSocket = null
        }
    }

    override fun onDestroy() {
        closed = true
        worker.shutdownNow()
        try { activeSocket?.close() } catch (_: IOException) { }
    }
}
