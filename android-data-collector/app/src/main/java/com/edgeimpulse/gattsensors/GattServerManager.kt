package com.edgeimpulse.gattsensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission

class GattServerManager(private val context: Context, private val bluetoothAdapter: BluetoothAdapter) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    // Some phones (and devices with BT off at app launch) return null here —
    // peripheral mode is optional, so don't crash if it's unavailable.
    @SuppressLint("MissingPermission")
    private val advertiser: BluetoothLeAdvertiser? = runCatching { bluetoothAdapter.bluetoothLeAdvertiser }.getOrNull()
    private var gattServer: BluetoothGattServer? = null
    private var registeredClient: BluetoothDevice? = null

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                registeredClient = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                registeredClient = null
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun startServer() {
        val adv = advertiser ?: return  // peripheral role not supported on this device
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(GattProfile.SERVICE_UUID))
            .build()

        adv.startAdvertising(settings, data, advertiseCallback)
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupGattService()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopServer() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
    }

    @SuppressLint("MissingPermission")
    private fun setupGattService() {
        val service = BluetoothGattService(GattProfile.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val accelerometerCharacteristic = BluetoothGattCharacteristic(
            GattProfile.ACCELEROMETER_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(accelerometerCharacteristic)
        gattServer?.addService(service)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun notifySensorData(data: SensorData) {
        val characteristic = gattServer
            ?.getService(GattProfile.SERVICE_UUID)
            ?.getCharacteristic(GattProfile.ACCELEROMETER_CHARACTERISTIC_UUID)

        characteristic?.let {
            // Convert the map of values to a comma-separated string
            it.value = data.values.values.joinToString(",").toByteArray(Charsets.UTF_8)
            registeredClient?.let { client ->
                gattServer?.notifyCharacteristicChanged(client, it, false)
            }
        }
    }
}
