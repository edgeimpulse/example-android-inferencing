/*
 * Copyright (c) 2025 EdgeImpulse Inc.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.edgeimpulse.gattsensors.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous 16 kHz mono PCM16 microphone reader.
 *
 * Audio is delivered to [onSlice] in fixed-size [sliceSamples]-long
 * [FloatArray]s (samples scaled to roughly +/-32768 to match how the EI model
 * was trained on int16 PCM).
 *
 * Requires [Manifest.permission.RECORD_AUDIO]; callers must request the
 * permission before invoking [start].
 */
class AudioCapture(
    val sampleRateHz: Int,
    val sliceSamples: Int,
    private val scope: CoroutineScope,
    private val onSlice: (FloatArray) -> Unit,
) {
    private var job: Job? = null
    private val running = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, sliceSamples * 2 /* int16 bytes */ * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed (state=${record.state})")
            running.set(false)
            return
        }

        job = scope.launch(Dispatchers.IO) {
            val intBuf = ShortArray(sliceSamples)
            try {
                record.startRecording()
                while (running.get()) {
                    var read = 0
                    while (read < sliceSamples && running.get()) {
                        val n = record.read(intBuf, read, sliceSamples - read)
                        if (n <= 0) {
                            Log.w(TAG, "AudioRecord.read returned $n")
                            break
                        }
                        read += n
                    }
                    if (read == sliceSamples) {
                        val out = FloatArray(sliceSamples)
                        for (i in 0 until sliceSamples) out[i] = intBuf[i].toFloat()
                        onSlice(out)
                    }
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = running.get()

    private companion object {
        const val TAG = "AudioCapture"
    }
}
