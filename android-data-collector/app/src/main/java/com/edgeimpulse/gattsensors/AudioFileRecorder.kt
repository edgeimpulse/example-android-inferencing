package com.edgeimpulse.gattsensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Capture a fixed-length 16 kHz mono PCM16 clip from the microphone and
 * upload it to Edge Impulse as a single WAV training sample. Holds the mic
 * exclusively for the duration of the capture, so callers must release any
 * always-on listener (the KWS engine in [voice.VoiceCommandManager]) first
 * via the mic-arbiter callbacks on [SensorViewModel].
 */
class AudioFileRecorder(private val context: Context) {

    private var job: Job? = null

    fun isRecording(): Boolean = job?.isActive == true

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * @param durationMs total clip length in ms
     * @param onComplete called from a background thread with the encoded WAV
     *                   bytes, or null if recording failed / was cancelled
     */
    @SuppressLint("MissingPermission")
    fun start(durationMs: Long, onComplete: (ByteArray?) -> Unit) {
        if (isRecording()) {
            onComplete(null); return
        }
        if (!hasPermission()) {
            Log.w("AudioFileRecorder", "RECORD_AUDIO not granted; audio capture skipped")
            onComplete(null); return
        }
        job = CoroutineScope(Dispatchers.IO).launch {
            val sampleRate = SAMPLE_RATE_HZ
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (e: Throwable) {
                Log.e("AudioFileRecorder", "AudioRecord init failed", e)
                onComplete(null); return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioFileRecorder", "AudioRecord not initialised")
                recorder.release(); onComplete(null); return@launch
            }

            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            recorder.startRecording()
            val end = System.currentTimeMillis() + durationMs
            try {
                while (System.currentTimeMillis() < end) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) pcm.write(buf, 0, n)
                }
            } catch (e: Throwable) {
                Log.e("AudioFileRecorder", "Read loop error", e)
            } finally {
                try { recorder.stop() } catch (_: Throwable) { }
                recorder.release()
            }

            val wav = encodeWav(pcm.toByteArray(), sampleRate)
            onComplete(wav)
        }
    }

    /** Cancel the in-flight capture (mic is released in the finally block). */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Prepend a 44-byte RIFF/WAVE header to raw PCM16 mono [pcm] bytes. */
    private fun encodeWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * 2 // mono, 16-bit
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                  // PCM chunk size
        header.putShort(1)                 // AudioFormat = PCM
        header.putShort(1)                 // channels
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)                 // block align
        header.putShort(16)                // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
