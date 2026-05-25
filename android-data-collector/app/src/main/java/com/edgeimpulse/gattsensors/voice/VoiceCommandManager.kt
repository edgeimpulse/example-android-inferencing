/*
 * Copyright (c) 2025 EdgeImpulse Inc.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.edgeimpulse.gattsensors.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.edgeimpulse.gattsensors.SensorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Glue layer that wires the "Hey Android" KWS wake word, on-device speech
 * recognition, the command parser, and the existing multi-modal recording
 * pipeline.
 *
 * Flow:
 *  1. [KwsEngine] listens continuously for "hey_android".
 *  2. On wake, mic is yielded to [OnDeviceStt] for one utterance.
 *  3. [VoiceCommandParser] extracts (duration, label) from the transcript.
 *  4. We call [SensorViewModel.startUnifiedRecording] with auto-selected
 *     sources: Zephyr (Nesso N1) preferred, fall back to Wear OS, then
 *     phone IMU/camera if nothing else is connected.
 */
class VoiceCommandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: SensorViewModel,
    private val onStatus: (String) -> Unit = {},
    private val onTranscript: (String) -> Unit = {},
    private val onWake: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val stt = OnDeviceStt(
        context = context,
        onResult = ::handleTranscript,
        onError  = { msg ->
            onStatus("STT: $msg")
            // STT released the mic — resume wake-word listening.
            kws?.start()
        },
    )

    private var kws: KwsEngine? = null

    val kwsTopLabel: StateFlow<String>? get() = kws?.topLabel
    val kwsTopScore: StateFlow<Float>? get() = kws?.topScore

    fun enable(): Boolean {
        if (_enabled.value) return true
        if (!KwsNative.isAvailable()) {
            onStatus("KWS native lib not loaded")
            return false
        }
        val engine = KwsEngine(scope = scope, onWake = ::handleWake)
        if (!engine.start()) {
            onStatus("Failed to start KWS")
            return false
        }
        kws = engine
        _enabled.value = true
        onStatus("Listening for 'hey android'")
        return true
    }

    fun disable() {
        kws?.stop()
        kws = null
        stt.release()
        _enabled.value = false
        onStatus("Voice control off")
    }

    private fun handleWake() {
        main.post {
            onStatus("Wake word detected; listening for command...")
            onWake()
            // SpeechRecognizer needs the mic, so pause KWS.
            kws?.stop()
            stt.listenOnce(maxMs = 5_000L)
        }
    }

    private fun handleTranscript(text: String) {
        onTranscript(text)
        val cmd = VoiceCommandParser.parse(text)
        if (cmd == null) {
            onStatus("Could not parse: '$text'")
            kws?.start() // resume wake listening
            return
        }
        Log.i(TAG, "Parsed command: $cmd")
        onStatus("Recording ${cmd.durationSeconds}s as '${cmd.label}'")
        startRecording(cmd)
        // Resume wake-word listening after the recorder kicks off.
        main.postDelayed({ kws?.start() }, 300L)
    }

    private fun startRecording(cmd: VoiceCommand) {
        // Auto source priority: Zephyr (Nesso) > Wear OS > phone IMU > camera.
        val zephyrUp = viewModel.zephyrConnected.value
        val wearUp   = !viewModel.wearNode.value.isNullOrBlank()

        // Always include phone sensors as a fallback if nothing else is up.
        val includePhone = !zephyrUp && !wearUp

        viewModel.startUnifiedRecording(
            durationMs = cmd.durationMs,
            label = cmd.label,
            includePhoneSensors = includePhone,
            includeWear = wearUp,
            includeZephyr = zephyrUp,
            cameraHelper = null,
        )
    }

    private companion object {
        const val TAG = "VoiceCommandManager"
    }
}
