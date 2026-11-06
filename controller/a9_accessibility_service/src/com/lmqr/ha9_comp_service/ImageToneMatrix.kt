package com.lmqr.ha9_comp_service

/**
 * Neutral is identity; contrast expands around mid-gray and brightness lifts pixels.
 * Grey darkness (owner, Oct 21 2026) expands around WHITE first: white stays white, black stays
 * black, every grey moves toward black (light-grey keyboard symbols vanished in fast e-ink modes;
 * mid-gray contrast made them lighter). darkness 50 -> v' = 2v - 1 before contrast.
 */
fun imageToneMatrix(brightness: Int, contrast: Int, darkness: Int = 0): FloatArray {
    val dark = 1f + darkness.coerceIn(0,99) / 50f
    val gain = 1f + contrast.coerceIn(0,99) / 50f
    val total = gain * dark
    val offset = gain * (1f - dark) + (1f - gain) * .5f + brightness.coerceIn(0,100) * .003f
    return floatArrayOf(total,0f,0f,0f, 0f,total,0f,0f, 0f,0f,total,0f, offset,offset,offset,1f)
}
