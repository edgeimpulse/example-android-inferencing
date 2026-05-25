/*
 * Copyright (c) 2025 EdgeImpulse Inc.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.edgeimpulse.gattsensors.voice

/**
 * JNI bridge to the Edge Impulse "hey_android" keyword spotting model.
 *
 * The native library is built from [app/src/main/cpp/CMakeLists.txt]. The model
 * expects PCM (16 kHz, mono, float, range +/-32768) fed one slice at a time
 * via [runSlice]; results are post-MAF probabilities indexed by [label].
 */
object KwsNative {

    @Volatile
    private var loaded: Boolean = false

    init {
        try {
            System.loadLibrary("eikws")
            loaded = true
        } catch (t: Throwable) {
            loaded = false
        }
    }

    fun isAvailable(): Boolean = loaded

    external fun initNative(): Int
    external fun deinitNative()
    external fun sliceSize(): Int
    external fun frequency(): Int
    external fun labelCount(): Int
    external fun label(idx: Int): String
    external fun runSlice(slice: FloatArray): FloatArray?
}
