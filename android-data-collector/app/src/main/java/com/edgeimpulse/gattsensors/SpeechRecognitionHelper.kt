package com.edgeimpulse.gattsensors

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechRecognitionHelper(private val context: Context) {

    private val _recognizedText = MutableStateFlow("")
    val recognizedText = _recognizedText.asStateFlow()

    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    init {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    _recognizedText.value = matches[0]
                }
            }

            override fun onReadyForSpeech(params: Bundle?) { /* Not needed */ }
            override fun onBeginningOfSpeech() { /* Not needed */ }
            override fun onRmsChanged(rmsdB: Float) { /* Not needed */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* Not needed */ }
            override fun onEndOfSpeech() { /* Not needed */ }
            override fun onError(error: Int) { /* Not needed */ }
            override fun onPartialResults(partialResults: Bundle?) { /* Not needed */ }
            override fun onEvent(eventType: Int, params: Bundle?) { /* Not needed */ }
        })
    }

    fun startListening() {
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    fun stopListening() {
        speechRecognizer.stopListening()
    }
}
