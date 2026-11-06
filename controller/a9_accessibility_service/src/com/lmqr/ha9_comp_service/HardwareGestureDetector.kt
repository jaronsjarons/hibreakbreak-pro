/*
 * This file contains code derived from the Android Open Source Project (AOSP).
 * The derived code is licensed under the Apache License, Version 2.0.
 * Modifications and additions are licensed under the MIT License.
 */

/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * The original file was heavily modified to work with hardware buttons and in Kotlin
 * So all not-relevant gestures and parts of code were removed and modified in a way to
 * handle hardware button events only
 */

package com.lmqr.ha9_comp_service

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewConfiguration

class HardwareGestureDetector(private val listener: OnGestureListener) {
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<Int, Runnable>()
    private val timer = object : ButtonGestureTimer {
        override fun cancel(id: Int) { callbacks.remove(id)?.let(handler::removeCallbacks) }
        override fun schedule(id: Int, at: Long, action: () -> Unit) {
            cancel(id)
            val callback = Runnable { callbacks.remove(id); action() }
            callbacks[id] = callback
            handler.postAtTime(callback, at)
        }
    }
    private val gesture = HardwareGestureState(timer,
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.getLongPressTimeout().toLong(),
        listener::onSinglePress, listener::onDoublePress, listener::onLongPress)

    interface OnGestureListener {
        fun onSinglePress()
        fun onDoublePress()
        fun onLongPress()
    }
    fun onKeyEvent(action: Int, eventTime: Long) {
        when (action) {
            KeyEvent.ACTION_DOWN -> gesture.down(eventTime)
            KeyEvent.ACTION_UP -> gesture.up(eventTime)
        }
    }
    fun cancel() = gesture.cancel()
}
