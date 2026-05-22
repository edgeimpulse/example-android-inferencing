package com.edgeimpulse.gattsensors

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.annotation.RequiresPermission

class BLEManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    // ✅ Clean scan logic
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(onDeviceFound: (BLEDevice) -> Unit) {
        scanner?.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val bleDevice = BLEDevice(
                    device = result.device,
                    name = result.scanRecord?.deviceName ?: "Unknown",
                    address = result.device.address,
                    rssi = result.rssi
                )
                onDeviceFound(bleDevice)
            }
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanner?.stopScan(object : ScanCallback() {})
    }
}
