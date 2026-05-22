package com.edgeimpulse.gattsensors

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

data class IngestionRequest(val protected: Protected, val payload: IngestionPayload)
data class Protected(val ver: String, val alg: String, val signature: String)
data class IngestionPayload(val device_name: String, val device_type: String, val interval_ms: Number, val sensors: List<SensorInfo>, val values: List<List<Float>>)

class DataRepository(private val context: Context, private val apiKeyStore: ApiKeyStore) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-ei-device"
    }

    // For offline logging
    private var isLoggingOffline = false
    private var csvFileWriter: FileWriter? = null
    private var offlineHeaders: List<String> = emptyList()

    // For remote management in-memory sampling
    private val remoteSampleData = mutableListOf<SensorData>()
    private var isSamplingForRemote = false

    fun startOfflineLogging(headers: List<String> = listOf("accelX", "accelY", "accelZ")) {
        if (isLoggingOffline) return
        isLoggingOffline = true
        offlineHeaders = listOf("timestamp") + headers
        val dir = File(context.getExternalFilesDir(null), "sensor_logs")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "sensor_data_${getDateString()}.csv")
        try {
            csvFileWriter = FileWriter(file)
            csvFileWriter?.append(offlineHeaders.joinToString(",") + "\n")
        } catch (e: IOException) {
            Log.e("DataRepository", "Error creating CSV file", e)
            csvFileWriter = null
            isLoggingOffline = false
        }
    }

    fun stopOfflineLogging() {
        if (!isLoggingOffline) return
        isLoggingOffline = false
        try {
            csvFileWriter?.flush()
            csvFileWriter?.close()
        } catch (e: IOException) {
            Log.e("DataRepository", "Error closing CSV file", e)
        }
        csvFileWriter = null
    }

    fun saveSensorData(data: SensorData) {
        if (isLoggingOffline) {
            try {
                val values = offlineHeaders.map { header -> if (header == "timestamp") data.timestamp.toString() else data.values[header]?.toString() ?: "" }
                csvFileWriter?.append(values.joinToString(",") + "\n")
            } catch (e: IOException) {
                Log.e("DataRepository", "Error writing to CSV file", e)
            }
        }
        if (isSamplingForRemote) {
            remoteSampleData.add(data)
        }
    }

    fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/sensor_data") {
            val data = String(messageEvent.data)
            val values = data.split(",").map { it.toFloat() }
            val sensorData = SensorData(System.currentTimeMillis(), mapOf("accelX" to values[0], "accelY" to values[1], "accelZ" to values[2]))
            saveSensorData(sensorData)
        }
    }

    suspend fun startRemoteSamplingAndCollect(timeoutMs: Int) {
        if (isSamplingForRemote) return
        isSamplingForRemote = true
        remoteSampleData.clear()
        delay(timeoutMs.toLong())
        isSamplingForRemote = false
    }

    fun uploadCollectedRemoteSample(
        label: String,
        hmacKey: String,
        path: String,
        sensorName: String,
        intervalMs: Int,
        lengthMs: Int
    ) {
        // Derive frequency and window length from the sample-request parameters.
        val frequencyHz = if (intervalMs > 0) 1000.0 / intervalMs else 62.5
        val maxSampleLengthS = (lengthMs / 1000).coerceAtLeast(1)
        val sensorInfo = SensorInfo(sensorName, listOf(frequencyHz), maxSampleLengthS)
        val values = remoteSampleData.map { it.values.values.toList() }
        val payload = IngestionPayload(deviceId, "ANDROID_PHONE", intervalMs.coerceAtLeast(1), listOf(sensorInfo), values)
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com$path")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .header("x-hmac-key", hmacKey)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute()
    }

    fun uploadStoredCsvFiles(label: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dir = File(context.getExternalFilesDir(null), "sensor_logs")
            if (!dir.exists()) return@launch

            dir.listFiles { f -> f.extension == "csv" }?.forEach { file ->
                uploadFile(file, label)
                file.delete()
            }
        }
    }

    private fun uploadFile(file: File, label: String) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("data", file.name, file.asRequestBody("text/csv".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/files")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DataRepository", "Failed to upload ${file.name}: ${response.body?.string()}")
            } else {
                Log.d("DataRepository", "Successfully uploaded ${file.name}")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Upload failed for ${file.name}", e)
        }
    }

    private fun getDateString(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    // -------------------------------------------------------------------------
    // Zephyr BLE relay
    // -------------------------------------------------------------------------

    /** Buffer that holds the most recent Zephyr sensor window for correlation. */
    private val pendingZephyrSensorData = mutableListOf<FloatArray>()

    fun saveZephyrInferenceResult(result: ZephyrInferenceResult) {
        // Build a sensor payload from the buffered raw sensor windows.
        // If no sensor windows are available yet, create a synthetic single-sample payload.
        val sensorWindows: List<List<Float>> = if (pendingZephyrSensorData.isNotEmpty()) {
            pendingZephyrSensorData.map { it.toList() }.also { pendingZephyrSensorData.clear() }
        } else {
            listOf(listOf(result.confidence))
        }

        val sensors = listOf(SensorInfo("Zephyr IMU", listOf(100), 600))
        val payload = IngestionPayload(
            device_name  = "zephyr-ei-monitor",
            device_type  = "ZEPHYR_IMU",
            interval_ms  = 10,
            sensors      = sensors,
            values       = sensorWindows
        )
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", result.label)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DataRepository", "Zephyr inference upload failed: ${response.body?.string()}")
            } else {
                Log.d("DataRepository", "Zephyr inference '${result.label}' uploaded")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Zephyr inference upload exception", e)
        }
    }

    fun saveZephyrSensorData(samples: FloatArray) {
        pendingZephyrSensorData.add(samples)
        if (isLoggingOffline) {
            try {
                csvFileWriter?.append(samples.joinToString(",") + "\n")
            } catch (e: IOException) {
                Log.e("DataRepository", "CSV write error", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Camera / image upload
    // -------------------------------------------------------------------------

    /**
     * Upload a raw JPEG [imageBytes] to Edge Impulse ingestion as a training image.
     * [label] is the EI data label (e.g. "normal", "anomaly").
     */
    fun uploadImage(imageBytes: ByteArray, label: String) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "data",
                "image_${System.currentTimeMillis()}.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/files")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(multipart)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("DataRepository", "Image upload failed: ${response.body?.string()}")
                } else {
                    Log.d("DataRepository", "Image uploaded with label='$label'")
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Image upload exception", e)
            }
        }
    }
}
