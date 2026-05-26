package com.edgeimpulse.gattsensors

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            val bluetoothManager =
                application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            val apiKeyStore         = ApiKeyStore(application)
            val voiceSettingsStore  = VoiceSettingsStore(application)
            val deviceId            = Settings.Secure.getString(
                application.contentResolver, Settings.Secure.ANDROID_ID
            ) ?: "android-ei-device"
            val dataRepository      = DataRepository(application, apiKeyStore)
            val gattServerManager   = GattServerManager(application, bluetoothAdapter)
            val collector           = SensorCollector(application, dataRepository, gattServerManager)
            val locationCollector   = LocationCollector(application, dataRepository)
            val audioRecorder       = AudioFileRecorder(application)
            val edgeImpulseManager  = EdgeImpulseManager(apiKeyStore, dataRepository, deviceId)
            val zephyrBLEClient     = ZephyrBLEClient(application, dataRepository)
            val wearOSClient        = WearOSClient(application)
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(
                application, collector, gattServerManager,
                edgeImpulseManager, dataRepository, zephyrBLEClient,
                wearOSClient, apiKeyStore, voiceSettingsStore,
                locationCollector, audioRecorder
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

