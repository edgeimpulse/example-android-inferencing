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
 * We request offline recognition where supported (Android 6+ with the
 * appropriate language pack downloaded; on Pixel devices this is bundled).
 * Results arrive on the main thread via [onResult] (final transcript) or
 * [onError] (recognizer error code).
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

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listenOnce(maxMs: Long = 5_000L) {
        main.post {
            if (!isAvailable()) {
                onError("SpeechRecognizer not available on this device")
                return@post
            }
            release()
            val rec = if (Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error: $error")
                    onError("STT error code $error")
                    release()
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty()
                    Log.i(TAG, "STT result='$text'")
                    onResult(text)
                    release()
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            rec.startListening(intent)
            main.postDelayed({ recognizer?.stopListening() }, maxMs)
        }
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
