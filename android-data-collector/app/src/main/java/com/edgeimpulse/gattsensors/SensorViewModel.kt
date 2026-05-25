package com.edgeimpulse.gattsensors

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SensorViewModel(
    application: Application,
    private val sensorCollector: SensorCollector,
    private val gattServerManager: GattServerManager,
    private val edgeImpulseManager: EdgeImpulseManager,
    private val dataRepository: DataRepository,
    val zephyrBLEClient: ZephyrBLEClient,
    val apiKeyStore: ApiKeyStore
) : AndroidViewModel(application) {

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData = _sensorData.asStateFlow()

    private val _isCollecting = MutableStateFlow(false)
    val isCollecting = _isCollecting.asStateFlow()

    /** EI remote-management connection state (set by EdgeImpulseManager). */
    val eiConnected     = edgeImpulseManager.isConnected
    val eiConnectionError = edgeImpulseManager.connectionError

    private val speechRecognitionHelper = SpeechRecognitionHelper(application)
    val recognizedText = speechRecognitionHelper.recognizedText

    // Expose Zephyr state for the UI
    val zephyrConnected   = zephyrBLEClient.isConnected
    val zephyrInference   = zephyrBLEClient.latestInference
    val zephyrDevices     = zephyrBLEClient.scannedDevices
    val zephyrLabel       = zephyrBLEClient.currentLabel
    val zephyrSampleCount = zephyrBLEClient.sampleCount

    /** True while a Nesso recording window (and matching phone capture) is active. */
    private val _zephyrRecording = MutableStateFlow(false)
    val zephyrRecording = _zephyrRecording.asStateFlow()

    private var zephyrRecordingJob: Job? = null

    init {
        viewModelScope.launch {
            sensorCollector.dataFlow.collect {
                _sensorData.value = it
            }
        }
        edgeImpulseManager.connect()
    }

    private var durationJob: Job? = null

    fun startSensor(sensorType: String = "Accelerometer") {
        _isCollecting.value = true
        gattServerManager.startServer()
        sensorCollector.start(sensorType)
    }

    /** Start collecting for exactly [durationMs] ms, then auto-stop. */
    fun startSensorForDuration(sensorType: String, durationMs: Long) {
        if (_isCollecting.value) return
        _isCollecting.value = true
        gattServerManager.startServer()
        sensorCollector.start(sensorType)
        durationJob = viewModelScope.launch {
            delay(durationMs)
            stopSensor()
        }
    }

    fun stopSensor() {
        durationJob?.cancel()
        durationJob = null
        sensorCollector.stop()
        gattServerManager.stopServer()
        _isCollecting.value = false
    }

    fun reconnectEI() = edgeImpulseManager.reconnect()

    fun startListening() {
        speechRecognitionHelper.startListening()
    }

    fun startOfflineLogging(headers: List<String> = listOf("accelX", "accelY", "accelZ")) {
        dataRepository.startOfflineLogging(headers)
    }

    fun stopOfflineLogging() {
        dataRepository.stopOfflineLogging()
    }

    fun uploadStoredCsvFiles(label: String) {
        dataRepository.uploadStoredCsvFiles(label)
    }

    fun captureAndUploadImage(cameraHelper: CameraHelper, label: String) {
        cameraHelper.captureJpeg { jpegBytes ->
            viewModelScope.launch {
                dataRepository.uploadImage(jpegBytes, label)
            }
        }
    }

    /** Push a new label to the Nesso firmware via the STATE characteristic. */
    fun setZephyrLabel(label: String) {
        zephyrBLEClient.setLabel(label)
    }

    /**
     * Record from the Nesso N1 IMU for [durationMs], in lock-step with the
     * phone's local sensor collection. Both streams use the same window so
     * samples line up in time. On completion the Nesso buffer is uploaded
     * to Edge Impulse tagged with the currently-selected label.
     */
    fun startZephyrRecording(durationMs: Long, sensorType: String = "Accelerometer") {
        if (_zephyrRecording.value) return
        val label = zephyrLabel.value
        _zephyrRecording.value = true

        // Make sure the firmware also knows the active label (so on-device logs
        // and any local model gate on the same value).
        zephyrBLEClient.setLabel(label)
        zephyrBLEClient.resetSampleCount()
        dataRepository.startZephyrRecording()

        // Mirror the phone-side collection so both streams capture the same
        // window. Reuses the existing duration mechanism.
        startSensorForDuration(sensorType, durationMs)

        zephyrRecordingJob = viewModelScope.launch {
            delay(durationMs)
            dataRepository.stopZephyrRecordingAndUpload(label)
            _zephyrRecording.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Make sure all sensor / radio resources are released so we don't drain
        // battery (or leak the ViewModel via stale listeners) after the host
        // Activity is destroyed.
        sensorCollector.stop()
        gattServerManager.stopServer()
        edgeImpulseManager.disconnect()
        dataRepository.stopOfflineLogging()
    }
}

