package com.edgeimpulse.gattsensors

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorViewModel::class.java)) {
            val bluetoothManager =
                application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            val dataRepository      = DataRepository(application)
            val gattServerManager   = GattServerManager(application, bluetoothAdapter)
            val collector           = SensorCollector(application, dataRepository, gattServerManager)
            val edgeImpulseManager  = EdgeImpulseManager(BuildConfig.EI_API_KEY, dataRepository)
            val zephyrBLEClient     = ZephyrBLEClient(application, dataRepository)
            @Suppress("UNCHECKED_CAST")
            return SensorViewModel(
                application, collector, gattServerManager,
                edgeImpulseManager, dataRepository, zephyrBLEClient
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

