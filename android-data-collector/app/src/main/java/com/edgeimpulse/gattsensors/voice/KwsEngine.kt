/*
 * Copyright (c) 2025 EdgeImpulse Inc.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.edgeimpulse.gattsensors.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous "hey_android" keyword spotter built on top of [KwsNative] and
 * [AudioCapture]. Calls [onWake] when the wake-word score crosses [threshold]
 * (with a cool-down so a single utterance only fires once).
 */
class KwsEngine(
    private val scope: CoroutineScope,
    private val wakeLabel: String = "hey_android",
    private val threshold: Float = 0.80f,
    private val cooldownMs: Long = 1500L,
    private val onWake: () -> Unit,
) {
    private val _topScore = MutableStateFlow(0f)
    val topScore: StateFlow<Float> = _topScore.asStateFlow()

    private val _topLabel = MutableStateFlow("")
    val topLabel: StateFlow<String> = _topLabel.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var capture: AudioCapture? = null
    private var wakeIndex: Int = -1
    private var labels: List<String> = emptyList()
    private var lastFireMs: Long = 0L

    fun start(): Boolean {
        if (_running.value) return true
        if (!KwsNative.isAvailable()) {
            Log.e(TAG, "Native lib unavailable")
            return false
        }
        KwsNative.initNative()
        val sliceSize = KwsNative.sliceSize()
        val freq = KwsNative.frequency()
        labels = (0 until KwsNative.labelCount()).map { KwsNative.label(it) }
        wakeIndex = labels.indexOfFirst { it.equals(wakeLabel, ignoreCase = true) }
        Log.i(TAG, "labels=$labels wakeIndex=$wakeIndex slice=$sliceSize freq=$freq")

        capture = AudioCapture(freq, sliceSize, scope) { slice -> onSlice(slice) }
        capture?.start()
        _running.value = true
        return true
    }

    fun stop() {
        capture?.stop()
        capture = null
        _running.value = false
        KwsNative.deinitNative()
    }

    private fun onSlice(slice: FloatArray) {
        val scores = KwsNative.runSlice(slice) ?: return
        var bestIdx = 0
        var bestVal = scores[0]
        for (i in 1 until scores.size) {
            if (scores[i] > bestVal) { bestVal = scores[i]; bestIdx = i }
        }
        _topScore.value = bestVal
        _topLabel.value = if (bestIdx in labels.indices) labels[bestIdx] else ""

        if (wakeIndex >= 0 && bestIdx == wakeIndex && bestVal >= threshold) {
            val now = System.currentTimeMillis()
            if (now - lastFireMs >= cooldownMs) {
                lastFireMs = now
                Log.i(TAG, "WAKE (${labels[bestIdx]}=$bestVal)")
                onWake()
            }
        }
    }

    private companion object {
        const val TAG = "KwsEngine"
    }
}
