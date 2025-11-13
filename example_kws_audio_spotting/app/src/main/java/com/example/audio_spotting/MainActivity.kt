package com.example.audio_spotting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Minimal, robust wake-word demo using Edge Impulse + TFLite C++ (NDK).
 * - Streams mic, resamples to 16 kHz, feeds continuous classifier slices.
 * - Optional 5s Android Speech-to-Text window after wake word (if online).
 * - Includes waveform + RMS so users see activity even on emulators.
 */
class MainActivity : ComponentActivity() {

    companion object {
        init { System.loadLibrary("audiospot") } // must match CMake target
        private const val TAG = "AudioSpot"
        private const val REQ_AUDIO = 1001

        private const val MODEL_RATE = 16000           // EI audio models default
        private const val TARGET_CHUNK_MS = 64         // latency vs. CPU
        private const val STT_DURATION_MS = 5_000L     // window after wake word
        private const val COOLDOWN_MS = 2_000L         // avoid re-trigger spam
        private const val WAKE_LABEL_INDEX = 0         // change if labels differ
    }

    // JNI (classic names – see native-lib.cpp)
    private external fun getModelInfo(): String
    private external fun getSliceSize(): Int
    private external fun classifyAudioSlice(slice: FloatArray): FloatArray?

    // UI
    private lateinit var txtInfo: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtConfidence: TextView
    private lateinit var txtTop1: TextView
    private lateinit var txtRms: TextView
    private lateinit var txtTranscript: TextView
    private lateinit var txtThreshVal: TextView
    private lateinit var sbThreshold: SeekBar
    private lateinit var btnToggle: Button
    private lateinit var waveform: WaveformView

    // State
    @Volatile private var recording = false
    @Volatile private var inStt = false
    private var lastTriggerTime = 0L
    private var threshold = 0.70f

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class RecCtx(
        val rec: AudioRecord,
        val srcRate: Int,
        val chunkSamplesSrc: Int,
        val sourceName: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtInfo = findViewById(R.id.txtInfo)
        txtStatus = findViewById(R.id.txtStatus)
        txtConfidence = findViewById(R.id.txtConfidence)
        txtTop1 = findViewById(R.id.txtTop1)
        txtRms = findViewById(R.id.txtRms)
        txtTranscript = findViewById(R.id.txtTranscript)
        txtThreshVal = findViewById(R.id.txtThreshVal)
        sbThreshold = findViewById(R.id.sbThreshold)
        btnToggle = findViewById(R.id.btnToggle)
        waveform = findViewById(R.id.waveform)

        try { txtInfo.text = getModelInfo() }
        catch (e: Throwable) {
            txtInfo.text = "Native lib error: ${e.message}"
            Log.e(TAG, "JNI mismatch", e)
        }

        // Slider 0.10..0.99
        sbThreshold.max = 99
        sbThreshold.progress = (threshold * 100).roundToInt().coerceIn(10, 99)
        txtThreshVal.text = "Trigger ≥ ${"%.2f".format(sbThreshold.progress / 100f)}"
        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                threshold = p.coerceIn(10, 99) / 100f
                txtThreshVal.text = "Trigger ≥ ${"%.2f".format(threshold)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            if (!hasRecordPerm()) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
                return@setOnClickListener
            }
            if (!recording) startStreaming() else stopStreaming()
        }

        // Request mic permission once at launch (don’t auto-start)
        if (!hasRecordPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    private fun hasRecordPerm() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_AUDIO) {
            txtStatus.text = if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED)
                "Mic permission granted" else "Mic permission denied"
        }
    }

    // === Streaming mic → resample to 16 kHz → EI continuous classifier ===========
    private fun startStreaming() {
        if (inStt) return
        recording = true
        btnToggle.text = "Stop Listening"
        txtStatus.text = "Initializing…"
        txtTranscript.text = if (hasInternet()) "" else "No internet → confidence only"

        val sliceSize = runCatching { getSliceSize().coerceAtLeast(512) }.getOrDefault(8000)
        val sliceBuf = FloatArray(sliceSize)
        var sliceFill = 0
        var lastUi = 0L

        thread(start = true, name = "mic-thread") {
            try {
                if (!hasRecordPerm()) {
                    runOnUiThread { txtStatus.text = "Mic permission required" }
                    recording = false; return@thread
                }

                val rc = openWorkingRecorder() ?: run {
                    runOnUiThread {
                        txtStatus.text =
                            "Mic init failed (no supported rate/source).\n" +
                                    "Emulator: Extended controls → Microphone → Use host input."
                    }
                    recording = false; return@thread
                }

                val rec = rc.rec
                val srcRate = rc.srcRate
                val chunkSamplesSrc = rc.chunkSamplesSrc
                val chunkSrc = ShortArray(chunkSamplesSrc)
                val chunk16k = FloatArray(((chunkSamplesSrc.toLong() * MODEL_RATE) / srcRate).toInt() + 8)

                runOnUiThread {
                    txtStatus.text = "Listening… (rate=${rc.srcRate}Hz, source=${rc.sourceName}, slice=$sliceSize)"
                }

                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    runOnUiThread { txtStatus.text = "Mic not recording" }
                    recording = false; try { rec.release() } catch (_: Exception) {}
                    return@thread
                }

                while (recording && !inStt) {
                    val n = if (Build.VERSION.SDK_INT >= 23)
                        rec.read(chunkSrc, 0, chunkSrc.size, AudioRecord.READ_BLOCKING)
                    else rec.read(chunkSrc, 0, chunkSrc.size)
                    if (n <= 0) continue

                    val outLen = resampleShortToFloat16k(chunkSrc, n, srcRate, chunk16k)

                    // Throttle UI to ~10 Hz
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 100) {
                        lastUi = now
                        var sumSq = 0.0
                        for (i in 0 until outLen) sumSq += (chunk16k[i] * chunk16k[i])
                        val rms = sqrt(sumSq / max(1, outLen))
                        runOnUiThread {
                            txtRms.text = "RMS: ${"%.4f".format(rms)}"
                            waveform.append(chunk16k, outLen)
                        }
                    }

                    // Fill slice and classify
                    var off = 0
                    while (off < outLen) {
                        val can = minOf(sliceSize - sliceFill, outLen - off)
                        System.arraycopy(chunk16k, off, sliceBuf, sliceFill, can)
                        sliceFill += can; off += can

                        if (sliceFill == sliceSize) {
                            val probs = runCatching { classifyAudioSlice(sliceBuf) }.getOrNull()
                            sliceFill = 0
                            if (probs == null || probs.isEmpty()) continue

                            var topIdx = 0
                            var topVal = probs[0]
                            for (i in 1 until probs.size) if (probs[i] > topVal) { topVal = probs[i]; topIdx = i }
                            val wake = probs.getOrNull(WAKE_LABEL_INDEX) ?: 0f

                            runOnUiThread {
                                txtConfidence.text = "Confidence: ${"%.2f".format(wake)}"
                                txtTop1.text = "Top-1: idx=$topIdx  val=${"%.2f".format(topVal)}"
                            }

                            val cooledDown = System.currentTimeMillis() - lastTriggerTime > COOLDOWN_MS
                            if (wake >= threshold && cooledDown && !inStt) {
                                lastTriggerTime = System.currentTimeMillis()
                                runOnUiThread { txtStatus.text = "Wake word detected! (${(wake * 100).roundToInt()}%)" }
                                if (hasInternet()) runOnUiThread { startSttWindow(STT_DURATION_MS) }
                            }
                        }
                    }
                }

                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            } catch (e: Throwable) {
                runOnUiThread { txtStatus.text = "Crash: ${e.javaClass.simpleName}: ${e.message}" }
                Log.e(TAG, "Fatal in mic-thread", e)
            } finally {
                recording = false
                runOnUiThread { btnToggle.text = "Start Listening" }
            }
        }
    }

    private fun stopStreaming() {
        recording = false
        btnToggle.text = "Start Listening"
        txtStatus.text = "Idle"
    }

    // Probe sources/rates; swallow builder exceptions and keep trying.
    private fun openWorkingRecorder(): RecCtx? {
        val sources = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
        )
        val rates = listOf(16000, 44100, 48000, 22050, 11025, 8000)

        for ((src, srcName) in sources) for (rate in rates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val chunkSamplesSrc = max(256, rate * TARGET_CHUNK_MS / 1000)

            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    val rec = AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .build()
                    if (rec.state == AudioRecord.STATE_INITIALIZED)
                        return RecCtx(rec, rate, chunkSamplesSrc, srcName)
                    else rec.release()
                } catch (_: Throwable) { /* try legacy */ }
            }

            try {
                val rec = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                if (rec.state == AudioRecord.STATE_INITIALIZED)
                    return RecCtx(rec, rate, chunkSamplesSrc, srcName)
                else rec.release()
            } catch (_: Throwable) { /* keep probing */ }
        }
        return null
    }

    /** Linear resample Short PCM @inRate -> Float [-1..1] @16k. Returns output length. */
    private fun resampleShortToFloat16k(input: ShortArray, nIn: Int, inRate: Int, out: FloatArray): Int {
        if (inRate == MODEL_RATE) {
            for (i in 0 until nIn) out[i] = input[i] / 32768.0f
            return nIn
        }
        val outLen = ((nIn.toLong() * MODEL_RATE) / inRate).toInt().coerceAtMost(out.size)
        val step = inRate.toDouble() / MODEL_RATE
        var pos = 0.0
        for (j in 0 until outLen) {
            val i0 = pos.toInt().coerceIn(0, nIn - 1)
            val i1 = (i0 + 1).coerceAtMost(nIn - 1)
            val frac = (pos - i0).toFloat()
            val s0 = input[i0] / 32768.0f
            val s1 = input[i1] / 32768.0f
            out[j] = s0 + (s1 - s0) * frac
            pos += step
        }
        return outLen
    }

    // --------- STT (online only) -------------------------------------------------
    private fun startSttWindow(durationMs: Long) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            txtStatus.text = "Speech recognition not available"; return
        }
        inStt = true
        txtStatus.text = "Listening for command…"
        txtTranscript.text = ""

        val sr = SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onError(error: Int) { txtTranscript.text = "STT error: $error" }
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                txtTranscript.text = "Heard: $text"
            }
            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                txtTranscript.text = "…$text"
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.startListening(intent)

        mainHandler.postDelayed({
            try { sr.stopListening(); sr.cancel(); sr.destroy() } catch (_: Exception) {}
            inStt = false
            if (!recording) txtStatus.text = "Idle" else {
                txtStatus.text = "Resuming wake-word…"
                startStreaming()
            }
        }, durationMs)
    }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        recording = false
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
