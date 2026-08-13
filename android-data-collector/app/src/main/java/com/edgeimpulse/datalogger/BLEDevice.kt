package com.edgeimpulse.datalogger

import android.bluetooth.BluetoothDevice

data class BLEDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)
