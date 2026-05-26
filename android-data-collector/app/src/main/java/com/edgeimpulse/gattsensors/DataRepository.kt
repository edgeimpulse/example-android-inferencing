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
import kotlinx.coroutines.withContext
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

/** Metadata for one on-device CSV dataset shown in the Datasets tab. */
data class StoredDataset(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val sampleCount: Int,
    val createdAt: Long,
    val headers: List<String>,
)

/** First N rows of a CSV (header excluded) shown in the dataset previewer. */
data class DatasetPreview(val headers: List<String>, val rows: List<String>)

/**
 * Full in-memory view of a CSV dataset for the spreadsheet editor.
 * [rows] is a list of pre-split records (no header). Each row may legally
 * have a different length to [headers] — short rows are right-padded by the
 * editor UI; over-long rows have their trailing columns merged into the
 * last cell so nothing is silently lost on round-trip.
 */
data class EditableDataset(
    val file: File,
    val headers: List<String>,
    val rows: List<List<String>>,
)

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
    // When true, the header row is written from the first sample's actual
    // value keys rather than the caller-provided default. This avoids
    // empty columns when the UI hasn't told us which sensor is being
    // recorded (e.g. gyro samples keyed `gyro_0` arriving while headers
    // default to `accelX`/`accelY`/`accelZ`).
    private var offlineHeadersDeferred = false

    // For remote management in-memory sampling
    private val remoteSampleData = mutableListOf<SensorData>()
    private var isSamplingForRemote = false

    fun startOfflineLogging(headers: List<String> = emptyList()) {
        if (isLoggingOffline) return
        isLoggingOffline = true
        offlineHeadersDeferred = headers.isEmpty()
        offlineHeaders = if (offlineHeadersDeferred) listOf("timestamp")
                         else listOf("timestamp") + headers
        val dir = File(context.getExternalFilesDir(null), "sensor_logs")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "sensor_data_${getDateString()}.csv")
        try {
            csvFileWriter = FileWriter(file)
            if (!offlineHeadersDeferred) {
                csvFileWriter?.append(offlineHeaders.joinToString(",") + "\n")
            }
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

    // Flush CSV every N writes so an app crash doesn't lose buffered data.
    private var csvWriteCount = 0
    private val csvFlushInterval = 20

    fun saveSensorData(data: SensorData) {
        if (isLoggingOffline) {
            try {
                // Lazily fix the header row to whatever the first real sample
                // carries — guarantees the value columns line up with the
                // keys we look up below.
                if (offlineHeadersDeferred && data.values.isNotEmpty()) {
                    offlineHeaders = listOf("timestamp") + data.values.keys.toList()
                    csvFileWriter?.append(offlineHeaders.joinToString(",") + "\n")
                    offlineHeadersDeferred = false
                }
                val values = offlineHeaders.map { header -> if (header == "timestamp") data.timestamp.toString() else data.values[header]?.toString() ?: "" }
                csvFileWriter?.append(values.joinToString(",") + "\n")
                if (++csvWriteCount % csvFlushInterval == 0) {
                    csvFileWriter?.flush()
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Error writing to CSV file", e)
            }
        }
        if (isSamplingForRemote) {
            remoteSampleData.add(data)
        }
    }

    fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearProtocol.PATH_LEGACY_ACCEL -> {
                // Legacy "x,y,z" CSV — kept for old wear builds.
                val data = String(messageEvent.data)
                val values = data.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (values.size >= 3) {
                    saveSensorData(SensorData(
                        System.currentTimeMillis(),
                        mapOf("accelX" to values[0], "accelY" to values[1], "accelZ" to values[2])
                    ))
                    appendWearSamples("accel", System.currentTimeMillis(),
                        floatArrayOf(values[0], values[1], values[2]))
                }
            }
            WearProtocol.PATH_SAMPLES -> {
                // `<key>|<ts>|v0,v1,v2` per line — multiple lines per message.
                String(messageEvent.data).lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split('|', limit = 3)
                    if (parts.size != 3) return@forEach
                    val key = parts[0]
                    val ts  = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                    val vs  = parts[2].split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (vs.isNotEmpty()) appendWearSamples(key, ts, vs.toFloatArray())
                }
            }
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

    // -------------------------------------------------------------------------
    // On-device dataset management (browse / preview / rename / delete /
    // share / upload individual files before deciding what to keep).
    // -------------------------------------------------------------------------

    /** Folder where offline-logged CSVs live. Created lazily. */
    fun datasetsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "sensor_logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * List every CSV in the dataset folder along with cheap-to-compute
     * metadata (size, sample count = non-header lines, creation time, and the
     * comma-separated headers from line 1). Sample count requires reading the
     * file once so this is O(total bytes) — fine for the modest CSVs the app
     * produces, but call from a background dispatcher.
     */
    fun listStoredDatasets(): List<StoredDataset> {
        val files = datasetsDir().listFiles { f -> f.extension == "csv" } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            var headers: List<String> = emptyList()
            var sampleCount = 0
            try {
                f.bufferedReader().use { br ->
                    val first = br.readLine()
                    if (first != null) {
                        headers = first.split(',').map { it.trim() }
                        while (br.readLine() != null) sampleCount++
                    }
                }
            } catch (e: IOException) {
                Log.w("DataRepository", "Failed to read ${f.name}", e)
            }
            StoredDataset(
                file        = f,
                name        = f.name,
                sizeBytes   = f.length(),
                sampleCount = sampleCount,
                createdAt   = f.lastModified(),
                headers     = headers
            )
        }
    }

    /** First [maxRows] data rows of a dataset (header excluded), as raw CSV lines. */
    fun previewDataset(file: File, maxRows: Int = 50): DatasetPreview {
        val rows = mutableListOf<String>()
        var headers = emptyList<String>()
        try {
            file.bufferedReader().use { br ->
                val first = br.readLine() ?: return DatasetPreview(emptyList(), emptyList())
                headers = first.split(',').map { it.trim() }
                var line = br.readLine()
                while (line != null && rows.size < maxRows) {
                    rows.add(line)
                    line = br.readLine()
                }
            }
        } catch (e: IOException) {
            Log.w("DataRepository", "Preview failed for ${file.name}", e)
        }
        return DatasetPreview(headers, rows)
    }

    /**
     * Rename a dataset on disk. [newName] may omit the .csv extension; one is
     * added automatically. Returns the renamed file, or null on failure
     * (e.g. target already exists, or the file is currently being written to).
     */
    fun renameDataset(file: File, newName: String): File? {
        if (isLoggingOffline) return null  // refuse while the writer is open
        val safe = newName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotEmpty() } ?: return null
        val target = File(file.parentFile, if (safe.endsWith(".csv")) safe else "$safe.csv")
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    /** Delete a dataset. Returns true on success. */
    fun deleteDataset(file: File): Boolean = file.delete()

    // -- Spreadsheet-style editor support -------------------------------------

    /**
     * Load an entire CSV into memory for in-place editing. Caller should
     * invoke from a background dispatcher; medium-sized logs (~MB) are fine
     * but huge files will block.
     */
    fun loadDatasetFull(file: File): EditableDataset {
        val rows = mutableListOf<List<String>>()
        var headers: List<String> = emptyList()
        try {
            file.bufferedReader().use { br ->
                val first = br.readLine() ?: return EditableDataset(file, emptyList(), emptyList())
                headers = first.split(',').map { it.trim() }
                var line = br.readLine()
                while (line != null) {
                    rows.add(line.split(','))
                    line = br.readLine()
                }
            }
        } catch (e: IOException) {
            Log.w("DataRepository", "Full load failed for ${file.name}", e)
        }
        return EditableDataset(file, headers, rows)
    }

    /**
     * Overwrite [file] with [headers] and [rows]. Writes via a temp file and
     * atomic rename so a crash mid-write can't corrupt the original.
     * Refuses while offline logging is open.
     */
    fun writeDataset(file: File, headers: List<String>, rows: List<List<String>>): Boolean {
        if (isLoggingOffline) return false
        return try {
            val tmp = File(file.parentFile, "${file.nameWithoutExtension}.tmp.csv")
            FileWriter(tmp).use { w ->
                w.append(headers.joinToString(",")).append('\n')
                rows.forEach { row -> w.append(row.joinToString(",")).append('\n') }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: IOException) {
            Log.e("DataRepository", "writeDataset ${file.name} failed", e); false
        }
    }

    /**
     * Save [rows] under a fresh file in the datasets directory. [baseName]
     * may omit `.csv`; characters outside [A-Za-z0-9._-] are sanitised. If a
     * file with that name already exists, a `_2`, `_3`, … suffix is appended.
     * Returns the new file, or null on IO error.
     */
    fun writeDatasetAs(
        baseName: String,
        headers: List<String>,
        rows: List<List<String>>,
    ): File? {
        val safe = baseName.trim()
            .removeSuffix(".csv")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "snippet_${getDateString()}" }
        var target = File(datasetsDir(), "$safe.csv")
        var n = 2
        while (target.exists()) { target = File(datasetsDir(), "${safe}_$n.csv"); n++ }
        return if (writeDataset(target, headers, rows)) target else null
    }

    /**
     * Upload a single dataset to Edge Impulse tagged with [label]. When
     * [deleteAfter] is true the file is removed on a successful 2xx response.
     * Reports progress via the returned suspend result.
     */
    suspend fun uploadDataset(file: File, label: String, deleteAfter: Boolean): Result<Unit> {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data", file.name,
                    file.asRequestBody("text/csv".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://ingestion.edgeimpulse.com/api/training/files")
                .header("x-api-key", apiKeyStore.get())
                .header("x-label", label)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("HTTP ${resp.code}: ${resp.body?.string()}"))
                }
                if (deleteAfter) file.delete()
                Result.success(Unit)
            }
        } catch (e: IOException) {
            Result.failure(e)
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

    /** True while a user-initiated Nesso recording window is open. */
    @Volatile private var isZephyrRecording = false

    /**
     * Begin a Nesso N1 capture window. Clears any buffered samples so the
     * upload contains only data from this window.
     */
    fun startZephyrRecording() {
        synchronized(pendingZephyrSensorData) {
            pendingZephyrSensorData.clear()
        }
        isZephyrRecording = true
    }

    /**
     * Close the current Nesso N1 capture window and upload the buffered
     * samples to Edge Impulse tagged with [label]. [intervalMs] should match
     * the firmware sampling interval (default 10 ms / 100 Hz).
     */
    fun stopZephyrRecordingAndUpload(label: String, intervalMs: Int = 10) {
        isZephyrRecording = false
        val windows: List<FloatArray>
        synchronized(pendingZephyrSensorData) {
            if (pendingZephyrSensorData.isEmpty()) {
                Log.w("DataRepository", "Nesso recording for '$label' is empty — skipping upload")
                return
            }
            windows = pendingZephyrSensorData.toList()
            pendingZephyrSensorData.clear()
        }
        val rows: List<List<Float>> = windows.map { it.toList() }

        val sensors = listOf(SensorInfo("Nesso N1 IMU", listOf(1000.0 / intervalMs), 600))
        val payload = IngestionPayload(
            device_name  = "nesso-n1",
            device_type  = "NESSO_N1_IMU",
            interval_ms  = intervalMs,
            sensors      = sensors,
            values       = rows
        )
        val requestBody = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("DataRepository",
                        "Nesso recording upload failed: ${response.body?.string()}")
                } else {
                    Log.d("DataRepository",
                        "Nesso recording '$label' (${rows.size} samples) uploaded")
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Nesso recording upload exception", e)
            }
        }
    }

    fun saveZephyrInferenceResult(result: ZephyrInferenceResult) {
        // Build a sensor payload from the buffered raw sensor windows. If no sensor
        // data has been received yet, skip the upload entirely — uploading the
        // confidence value as a fake feature would poison the dataset.
        if (pendingZephyrSensorData.isEmpty()) {
            Log.w("DataRepository", "No sensor data buffered for inference '${result.label}'; skipping upload")
            return
        }
        val sensorWindows: List<List<Float>> =
            pendingZephyrSensorData.map { it.toList() }.also { pendingZephyrSensorData.clear() }

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
        synchronized(pendingZephyrSensorData) {
            pendingZephyrSensorData.add(samples)
        }
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
    suspend fun uploadImage(imageBytes: ByteArray, label: String): Boolean {
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

        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (!response.isSuccessful) {
                Log.e("DataRepository", "Image upload failed: ${response.body?.string()}")
            } else {
                Log.d("DataRepository", "Image uploaded with label='$label'")
            }
            response.isSuccessful
        } catch (e: IOException) {
            Log.e("DataRepository", "Image upload exception", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Microphone / audio upload
    // -------------------------------------------------------------------------

    /**
     * Upload a raw WAV [wavBytes] clip to Edge Impulse ingestion. Use this
     * for one-shot microphone captures (16 kHz mono PCM16 is the standard
     * EI audio format).
     */
    fun uploadAudio(wavBytes: ByteArray, label: String) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "data",
                "audio_${System.currentTimeMillis()}.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
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
                    Log.e("DataRepository", "Audio upload failed: ${response.body?.string()}")
                } else {
                    Log.d("DataRepository", "Audio uploaded with label='$label'")
                }
            } catch (e: IOException) {
                Log.e("DataRepository", "Audio upload exception", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Unified multi-modal capture (phone sensors + Wear OS samples)
    // -------------------------------------------------------------------------

    /** Per-sensor sample buffer keyed by canonical sensor key (e.g. "accel"). */
    private data class SensorBuffer(
        val key: String,
        val values: MutableList<FloatArray> = mutableListOf(),
    )

    private val phoneBuffers = mutableMapOf<String, SensorBuffer>()
    private val wearBuffers  = mutableMapOf<String, SensorBuffer>()
    @Volatile private var isMultiRecording = false

    /** Start a unified capture window. Clears all per-sensor buffers. */
    fun startMultiRecording() {
        synchronized(phoneBuffers) { phoneBuffers.clear() }
        synchronized(wearBuffers)  { wearBuffers.clear()  }
        isMultiRecording = true
    }

    /** Append one phone-sensor sample to the unified buffer. */
    fun appendPhoneSample(sensorKey: String, values: FloatArray) {
        if (!isMultiRecording) return
        synchronized(phoneBuffers) {
            phoneBuffers.getOrPut(sensorKey) { SensorBuffer(sensorKey) }
                .values.add(values)
        }
    }

    /** Append one Wear OS sample (called from message route). */
    fun appendWearSamples(sensorKey: String, @Suppress("UNUSED_PARAMETER") timestampMs: Long,
                          values: FloatArray) {
        if (!isMultiRecording) return
        synchronized(wearBuffers) {
            wearBuffers.getOrPut(sensorKey) { SensorBuffer(sensorKey) }
                .values.add(values)
        }
    }

    /**
     * Close the unified window and upload each buffered sensor stream as
     * its own Edge Impulse ingestion sample. All samples share [label] so
     * they line up in the dataset and can be combined downstream.
     */
    fun stopMultiRecordingAndUpload(label: String, durationMs: Long) {
        isMultiRecording = false
        val phoneSnap: Map<String, List<FloatArray>>
        val wearSnap:  Map<String, List<FloatArray>>
        synchronized(phoneBuffers) {
            phoneSnap = phoneBuffers.mapValues { it.value.values.toList() }
            phoneBuffers.clear()
        }
        synchronized(wearBuffers) {
            wearSnap = wearBuffers.mapValues { it.value.values.toList() }
            wearBuffers.clear()
        }
        if (phoneSnap.isEmpty() && wearSnap.isEmpty()) {
            Log.w("DataRepository", "Multi-recording '$label' empty — skipping upload")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            phoneSnap.forEach { (key, rows) ->
                if (rows.isNotEmpty()) {
                    uploadStream("phone-$key", "ANDROID_PHONE_$key".uppercase(),
                                 rows, label, durationMs)
                }
            }
            wearSnap.forEach { (key, rows) ->
                if (rows.isNotEmpty()) {
                    uploadStream("wear-$key", "WEAROS_$key".uppercase(),
                                 rows, label, durationMs)
                }
            }
        }
    }

    private fun uploadStream(
        sensorName: String,
        deviceType: String,
        rows: List<FloatArray>,
        label: String,
        durationMs: Long,
    ) {
        // Derive a per-sample interval from the actual sample count over the
        // capture window. Edge Impulse uses this to plot the time axis.
        val intervalMs = if (rows.size > 1) (durationMs.toDouble() / rows.size) else 10.0
        val freqHz     = if (intervalMs > 0) 1000.0 / intervalMs else 100.0
        val maxLenS    = ((durationMs / 1000).coerceAtLeast(1)).toInt()

        val sensors = listOf(SensorInfo(sensorName, listOf(freqHz), maxLenS))
        val values  = rows.map { it.toList() }
        val payload = IngestionPayload(deviceId, deviceType, intervalMs, sensors, values)
        val body    = IngestionRequest(Protected("v1", "none", "00"), payload)

        val request = Request.Builder()
            .url("https://ingestion.edgeimpulse.com/api/training/data")
            .header("x-api-key", apiKeyStore.get())
            .header("x-label", label)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DataRepository",
                    "Multi upload '$sensorName' failed: ${response.body?.string()}")
            } else {
                Log.d("DataRepository",
                    "Multi upload '$sensorName' (${rows.size} samples) ok")
            }
        } catch (e: IOException) {
            Log.e("DataRepository", "Multi upload '$sensorName' exception", e)
        }
    }
}
