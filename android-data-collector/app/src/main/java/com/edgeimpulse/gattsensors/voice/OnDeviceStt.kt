/*
 * Copyright (c) 2025 EdgeImpulse Inc.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.edgeimpulse.gattsensors.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech-to-text using Android's [SpeechRecognizer].
 *
 * Tries the on-device recognizer first (Android 12+ via
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer]); transparently falls back
 * to the network recognizer if the on-device engine errors out (common on
 * non-Pixel devices that don't ship the offline model). Final transcripts
 * arrive on the main thread via [onResult]; recognizer errors via [onError].
 *
 * NOTE: [SpeechRecognizer] is intentionally one-shot — call [listenOnce] from
 * the main thread; do not call it again until either callback fires.
 */
class OnDeviceStt(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var triedFallback: Boolean = false
    private var currentMaxMs: Long = 5_000L

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listenOnce(maxMs: Long = 5_000L) {
        main.post {
            if (!isAvailable()) {
                onError("SpeechRecognizer not available on this device")
                return@post
            }
            release()
            triedFallback = false
            currentMaxMs = maxMs
            startInternal(preferOnDevice = Build.VERSION.SDK_INT >= 31)
        }
    }

    private fun startInternal(preferOnDevice: Boolean) {
        val rec = try {
            if (preferOnDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createRecognizer failed (onDevice=$preferOnDevice): $t")
            if (preferOnDevice) {
                startInternal(preferOnDevice = false)
            } else {
                onError("STT init failed: ${t.message}")
            }
            return
        }
        recognizer = rec
        Log.i(TAG, "STT started (onDevice=$preferOnDevice)")
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "partial='${list?.firstOrNull().orEmpty()}'")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val name = errorName(error)
                Log.w(TAG, "SpeechRecognizer error: $error ($name) onDevice=$preferOnDevice fallbackTried=$triedFallback")
                // On-device engine may not have a language pack on this device.
                // Fall back to the network recognizer for codes that indicate
                // an engine / language problem rather than a real speech error.
                val shouldFallback = preferOnDevice && !triedFallback &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                     error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                     error == SpeechRecognizer.ERROR_CLIENT ||
                     error == SpeechRecognizer.ERROR_SERVER ||
                     error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                     error == 13 /* ERROR_NO_INTERNET on some OEMs */)
                if (shouldFallback) {
                    triedFallback = true
                    release()
                    main.postDelayed({ startInternal(preferOnDevice = false) }, 200L)
                } else {
                    onError("STT $name")
                    release()
                }
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                Log.i(TAG, "STT result='$text'")
                if (text.isBlank()) onError("STT empty result") else onResult(text)
                release()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            rec.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening threw: $t")
            onError("STT start failed: ${t.message}")
            release()
            return
        }
        main.postDelayed({ recognizer?.stopListening() }, currentMaxMs)
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        13 -> "ERROR_NO_INTERNET"
        else -> "error_$code"
    }

    fun release() {
        main.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private companion object {
        const val TAG = "OnDeviceStt"
    }
}
