package com.edgeimpulse.gattsensors

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val speechRecognitionHelper = SpeechRecognitionHelper(application)
    val recognizedText = speechRecognitionHelper.recognizedText

    // Expose Zephyr state for the UI
    val zephyrConnected = zephyrBLEClient.isConnected
    val zephyrInference = zephyrBLEClient.latestInference
    val zephyrDevices   = zephyrBLEClient.scannedDevices

    init {
        viewModelScope.launch {
            sensorCollector.dataFlow.collect {
                _sensorData.value = it
            }
        }
        edgeImpulseManager.connect()
    }

    fun startSensor(sensorType: String = "Accelerometer") {
        gattServerManager.startServer()
        sensorCollector.start(sensorType)
    }

    fun stopSensor() {
        sensorCollector.stop()
        gattServerManager.stopServer()
    }

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

    override fun onCleared() {
        super.onCleared()
        edgeImpulseManager.disconnect()
        dataRepository.stopOfflineLogging()
    }
}

