package com.edgeimpulse.gattsensors

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

data class Hello(val hello: HelloMessage)
data class HelloMessage(
    val version: Int,
    val apiKey: String,
    val deviceId: String,
    val deviceType: String,
    val connection: String,
    val sensors: List<SensorInfo>,
    val supportsSnapshotStreaming: Boolean
)
data class SensorInfo(
    val name: String,
    val frequencies: List<Number>,
    val maxSampleLengthS: Int
)

data class HelloResponse(val hello: Boolean, val err: String?)
data class SampleRequest(val sample: SampleRequestMessage)
data class SampleRequestMessage(val label: String, val length: Int, val path: String, val hmacKey: String, val interval: Int, val sensor: String)

class EdgeImpulseManager(private val apiKeyStore: ApiKeyStore, private val repository: DataRepository) {

    private val client: OkHttpClient
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val deviceId = "android-device"

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun connect() {
        val request = Request.Builder().url("wss://remote-mgmt.edgeimpulse.com").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("EdgeImpulseManager", "WebSocket opened")
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("EdgeImpulseManager", "Message received: $text")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.d("EdgeImpulseManager", "WebSocket closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("EdgeImpulseManager", "WebSocket failure: ${t.message}")
            }
        })
    }

    private fun sendHello() {
        val sensors = listOf(
            SensorInfo("Accelerometer", listOf(100, 62.5), 600),
            SensorInfo("PPG (Heart Rate)", listOf(), 600),
            SensorInfo("GPS", listOf(), 600),
            SensorInfo("Camera", listOf(), 60000),
            SensorInfo("EEG", listOf(), 600),
            SensorInfo("ECG", listOf(), 600)
        )
        val hello = Hello(HelloMessage(3, apiKeyStore.get(), deviceId, "ANDROID_PHONE", "daemon", sensors, true))
        webSocket?.send(gson.toJson(hello))
    }

    private fun handleMessage(text: String) {
        try {
            val helloResponse = gson.fromJson(text, HelloResponse::class.java)
            if (helloResponse.hello) {
                Log.d("EdgeImpulseManager", "Hello successful")
            } else {
                Log.e("EdgeImpulseManager", "Hello failed: ${helloResponse.err}")
            }
        } catch (e: Exception) { /* Not a hello response */ }

        try {
            val sampleRequest = gson.fromJson(text, SampleRequest::class.java)
            val sample = sampleRequest?.sample ?: return  // not a sample-request payload
            coroutineScope.launch {
                try {
                    // For now, we only support the accelerometer, ppg, camera, and eeg
                    if (sample.sensor == "Accelerometer") {
                        // Acknowledge the request
                        webSocket?.send("{\"sample\": true}")
                        // Start sampling
                        webSocket?.send("{\"sampleStarted\": true}")
                        repository.startRemoteSamplingAndCollect(sample.length)
                        // Notify that we are uploading
                        webSocket?.send("{\"sampleUploading\": true}")
                        repository.uploadCollectedRemoteSample(
                            sample.label,
                            sample.hmacKey,
                            sample.path,
                            sensorName = "Accelerometer"
                        )
                        // Notify that we are finished
                        webSocket?.send("{\"sampleFinished\": true}")
                    }
                } catch (t: Throwable) {
                    Log.e("EdgeImpulseManager", "sample handling failed", t)
                }
            }
        } catch (e: Exception) { /* Not a sample request */ }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
    }
}
