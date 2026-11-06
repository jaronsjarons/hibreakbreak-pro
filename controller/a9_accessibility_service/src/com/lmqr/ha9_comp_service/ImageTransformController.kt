package com.lmqr.ha9_comp_service

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/** AOSP SurfaceFlinger image transform. No light, waveform or calibration writes.
 * Android 15 SurfaceFlinger.cpp transaction 1015 uses a column-major 4x4 matrix.
 * This trial's system-UID app is authorized by SurfaceFlinger; SELinux stays enforcing.
 */
class ImageTransformController {
    private val thread = HandlerThread("HiBreakImageTransform").apply { start() }
    private val handler = Handler(thread.looper)
    private var closed = false

    @Synchronized fun apply(brightness: Int, contrast: Int, darkness: Int = 0) {
        if (closed) return
        handler.removeCallbacksAndMessages(null)
        handler.post { submit(imageToneMatrix(brightness, contrast, darkness)) }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        handler.post { submit(imageToneMatrix(0, 0)); thread.quitSafely() }
    }

    private fun submit(matrix: FloatArray) {
        val data = Parcel.obtain()
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null,"SurfaceFlinger") as IBinder
            data.writeInterfaceToken("android.ui.ISurfaceComposer")
            data.writeInt(1)
            matrix.forEach(data::writeFloat)
            check(binder.transact(1015, data, null, 0)) { "Image transform rejected" }
        } catch (e: Exception) {
            Log.e("HiBreakImageTransform", "Could not apply image adjustment", e)
        } finally { data.recycle() }
    }
}
