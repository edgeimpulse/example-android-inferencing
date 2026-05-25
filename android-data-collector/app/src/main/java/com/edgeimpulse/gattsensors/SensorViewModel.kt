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
    val wearOSClient: WearOSClient,
    val apiKeyStore: ApiKeyStore,
    private val locationCollector: LocationCollector,
    private val audioRecorder: AudioFileRecorder,
) : AndroidViewModel(application) {

    /**
     * Hooks the always-on KWS engine uses to claim/release the mic. Set by
     * [MainActivity] once [voice.VoiceCommandManager] exists. Audio capture
     * pauses the wake-word listener so the two don't fight for the
     * AudioRecord; KWS is resumed on completion.
     */
    var onMicAcquire: (() -> Unit)? = null
    var onMicRelease: (() -> Unit)? = null

    /** UI labels for the Collect-tab dropdown. */
    val collectSourceOptions: List<String> = buildList {
        addAll(sensorCollector.availableSensorLabels())
        add("GPS (Location)")
        add("Microphone (Audio)")
    }

    /** Display name of the connected Wear OS node, null if none. */
    val wearNode        = wearOSClient.connectedNode
    val wearSampleCount = wearOSClient.samplesReceived

    /** True while a unified multi-modal recording window is active. */
    private val _multiRecording = MutableStateFlow(false)
    val multiRecording = _multiRecording.asStateFlow()

    private var multiJob: Job? = null

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
        // GPS samples flow through the same StateFlow so the live readout in
        // the Collect tab updates regardless of which source is active.
        viewModelScope.launch {
            locationCollector.dataFlow.collect {
                _sensorData.value = it
            }
        }
        // Route Wear OS sample/event messages into the repository so the
        // unified recorder picks them up.
        viewModelScope.launch {
            WearEventBus.events.collect { ev ->
                dataRepository.onMessageReceived(ev)
            }
        }
        wearOSClient.refreshNodes()
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
        when (sensorType) {
            "GPS (Location)" -> {
                _isCollecting.value = true
                locationCollector.start()
                durationJob = viewModelScope.launch {
                    delay(durationMs)
                    locationCollector.stop()
                    _isCollecting.value = false
                }
            }
            "Microphone (Audio)" -> {
                _isCollecting.value = true
                // Release the always-on KWS so the recorder owns the mic.
                onMicAcquire?.invoke()
                val label = lastLabel.ifBlank { "audio" }
                audioRecorder.start(durationMs) { wav ->
                    if (wav != null) dataRepository.uploadAudio(wav, label)
                    onMicRelease?.invoke()
                    _isCollecting.value = false
                }
            }
            else -> {
                _isCollecting.value = true
                gattServerManager.startServer()
                sensorCollector.start(sensorType)
                durationJob = viewModelScope.launch {
                    delay(durationMs)
                    stopSensor()
                }
            }
        }
    }

    /** Last label entered in the Collect tab; used for audio uploads. */
    @Volatile var lastLabel: String = ""

    fun stopSensor() {
        durationJob?.cancel()
        durationJob = null
        sensorCollector.stop()
        locationCollector.stop()
        if (audioRecorder.isRecording()) {
            audioRecorder.cancel()
            onMicRelease?.invoke()
        }
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

    /**
     * Multi-modal capture window: fire every selected data source at the
     * same instant under a single [label], then upload each stream as its
     * own EI sample so they can be correlated downstream.
     *
     * @param durationMs window length
     * @param label EI training label (also pushed to Nesso STATE and Wear)
     * @param includePhoneSensors register every available SensorManager sensor
     * @param includeWear send start/stop command to the paired watch
     * @param includeZephyr open a Nesso BLE recording window in parallel
     * @param cameraHelper non-null = take one JPEG snapshot at session start
     */
    fun startUnifiedRecording(
        durationMs: Long,
        label: String,
        includePhoneSensors: Boolean = true,
        includeWear: Boolean = true,
        includeZephyr: Boolean = true,
        cameraHelper: CameraHelper? = null,
    ) {
        if (_multiRecording.value) return
        _multiRecording.value = true

        dataRepository.startMultiRecording()

        if (includePhoneSensors) {
            gattServerManager.startServer()
            sensorCollector.startAll()
            locationCollector.start()
            _isCollecting.value = true
        }
        if (includeWear) {
            wearOSClient.resetCount()
            wearOSClient.startRecording(label, durationMs)
        }
        if (includeZephyr && zephyrConnected.value) {
            zephyrBLEClient.setLabel(label)
            zephyrBLEClient.resetSampleCount()
            dataRepository.startZephyrRecording()
        }
        cameraHelper?.captureJpeg { jpeg ->
            viewModelScope.launch { dataRepository.uploadImage(jpeg, label) }
        }

        multiJob = viewModelScope.launch {
            delay(durationMs)
            if (includePhoneSensors) {
                sensorCollector.stop()
                locationCollector.stop()
                gattServerManager.stopServer()
                _isCollecting.value = false
            }
            if (includeWear) wearOSClient.stopRecording()
            if (includeZephyr && zephyrConnected.value) {
                dataRepository.stopZephyrRecordingAndUpload(label)
            }
            dataRepository.stopMultiRecordingAndUpload(label, durationMs)
            _multiRecording.value = false
        }
    }

    fun refreshWearNode() = wearOSClient.refreshNodes()

    override fun onCleared() {
        super.onCleared()
        // Make sure all sensor / radio resources are released so we don't drain
        // battery (or leak the ViewModel via stale listeners) after the host
        // Activity is destroyed.
        sensorCollector.stop()
        locationCollector.stop()
        if (audioRecorder.isRecording()) audioRecorder.cancel()
        gattServerManager.stopServer()
        edgeImpulseManager.disconnect()
        dataRepository.stopOfflineLogging()
    }
}

