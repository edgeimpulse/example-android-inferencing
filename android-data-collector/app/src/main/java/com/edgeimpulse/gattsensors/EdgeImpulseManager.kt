package com.edgeimpulse.gattsensors

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class EdgeImpulseManager(
    private val apiKeyStore: ApiKeyStore,
    private val repository: DataRepository,
    private val deviceId: String
) {

    private val client: OkHttpClient
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionError = MutableStateFlow("")
    val connectionError: StateFlow<String> = _connectionError.asStateFlow()

    @Volatile private var shouldReconnect = true
    private var reconnectJob: Job? = null

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
        shouldReconnect = true
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url("wss://remote-mgmt.edgeimpulse.com").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("EdgeImpulseManager", "WebSocket opened")
                _connectionError.value = ""
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("EdgeImpulseManager", "Message received: $text")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _isConnected.value = false
                Log.d("EdgeImpulseManager", "WebSocket closing: $code / $reason")
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                _connectionError.value = t.message ?: "Connection failed"
                Log.e("EdgeImpulseManager", "WebSocket failure: ${t.message}")
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    fun reconnect() {
        reconnectJob?.cancel()
        webSocket?.cancel()
        _isConnected.value = false
        openSocket()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch {
            delay(5_000)
            if (shouldReconnect) {
                Log.d("EdgeImpulseManager", "Reconnecting…")
                openSocket()
            }
        }
    }

    private fun sendHello() {
        // Only advertise sensors that have a fixed sampling frequency.
        // Sensors without frequencies (camera, GPS) cause a server-side
        // "sensor frequencies has length of zero" validation error.
        val sensors = listOf(
            SensorInfo("Accelerometer", listOf(62.5, 100), 600)
        )
        val hello = Hello(HelloMessage(3, apiKeyStore.get(), deviceId, "ANDROID_PHONE", "ip", sensors, true))
        webSocket?.send(gson.toJson(hello))
    }

    private fun handleMessage(text: String) {
        try {
            val helloResponse = gson.fromJson(text, HelloResponse::class.java)
            if (helloResponse.hello) {
                _isConnected.value = true
                _connectionError.value = ""
                Log.d("EdgeImpulseManager", "Hello successful — device registered")
            } else if (helloResponse.err != null) {
                _isConnected.value = false
                _connectionError.value = helloResponse.err
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
                            label       = sample.label,
                            hmacKey     = sample.hmacKey,
                            path        = sample.path,
                            sensorName  = "Accelerometer",
                            intervalMs  = sample.interval,
                            lengthMs    = sample.length
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
        shouldReconnect = false
        reconnectJob?.cancel()
        _isConnected.value = false
        webSocket?.close(1000, "Client disconnect")
    }
}
