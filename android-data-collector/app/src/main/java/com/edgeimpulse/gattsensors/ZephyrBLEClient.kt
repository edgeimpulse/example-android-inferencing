package com.edgeimpulse.gattsensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded inference result received from the Zephyr "EI-Monitor" device.
 *
 * Binary layout on ARM (matches inference_result_t in gatt_client.h):
 *   offset  0 : char label[32]
 *   offset 32 : float confidence
 *   offset 36 : uint32_t dsp_time_ms
 *   offset 40 : uint32_t classification_time_ms
 *   offset 44 : uint64_t timestamp    (4-byte aligned on ARM Cortex-M)
 *   Total: 52 bytes
 */
data class ZephyrInferenceResult(
    val label: String,
    val confidence: Float,
    val dspTimeMs: Int,
    val classificationTimeMs: Int,
    val zephyrTimestamp: Long,
    val receivedAt: Long = System.currentTimeMillis()
)

private const val TAG = "ZephyrBLEClient"
private const val TARGET_DEVICE_NAME = "EI-Monitor"
private const val INFERENCE_RESULT_SIZE = 52

/**
 * BLE central (GATT client) that:
 * 1. Scans for a Zephyr device advertising as "EI-Monitor"
 * 2. Connects and discovers the Edge Impulse GATT service
 * 3. Subscribes to inference + sensor characteristic notifications
 * 4. Forwards parsed data to [DataRepository] for Edge Impulse upload
 */
class ZephyrBLEClient(
    private val context: Context,
    private val dataRepository: DataRepository
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _latestInference = MutableStateFlow<ZephyrInferenceResult?>(null)
    val latestInference = _latestInference.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BLEDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val scannedDevicesLock = Any()

    // Pending: subscribe to sensor char after inference CCC write completes
    private var pendingSensorCharSubscription = false

    /* ---------------------------------------------------------------------- */
    /* Scanning                                                                 */
    /* ---------------------------------------------------------------------- */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val bleDevice = BLEDevice(result.device, name, result.device.address, result.rssi)

            // Accumulate for the UI list. BLE scan callbacks can arrive on different
            // threads, so the read-modify-write must be guarded.
            synchronized(scannedDevicesLock) {
                val current = _scannedDevices.value.toMutableList()
                if (current.none { it.address == bleDevice.address }) {
                    current.add(bleDevice)
                    _scannedDevices.value = current
                }
            }

            // Auto-connect to EI-Monitor
            if (name == TARGET_DEVICE_NAME && !_isConnected.value && bluetoothGatt == null) {
                Log.i(TAG, "Found $TARGET_DEVICE_NAME — connecting…")
                stopScan()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        _scannedDevices.value = emptyList()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filter = ScanFilter.Builder()
            .setDeviceName(TARGET_DEVICE_NAME)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "Scanning for $TARGET_DEVICE_NAME…")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /* ---------------------------------------------------------------------- */
    /* Connection                                                               */
    /* ---------------------------------------------------------------------- */

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(
            context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _isConnected.value = false
    }

    /* ---------------------------------------------------------------------- */
    /* GATT callbacks                                                           */
    /* ---------------------------------------------------------------------- */

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected — discovering services…")
                    _isConnected.value = true
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected")
                    _isConnected.value = false
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(GattProfile.EI_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "EI GATT service not found on device")
                return
            }

            Log.i(TAG, "EI GATT service discovered — enabling inference notifications")

            val inferenceChar = service.getCharacteristic(GattProfile.INFERENCE_CHAR_UUID)
            if (inferenceChar != null) {
                enableNotification(gatt, inferenceChar)
                pendingSensorCharSubscription = true  // subscribe sensor after CCC write
            } else {
                Log.w(TAG, "Inference characteristic not found")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "CCCD write failed (status=$status) for ${descriptor.characteristic.uuid}")
                // Reset pending flag so we don't get stuck waiting for a callback that
                // will never arrive with success.
                pendingSensorCharSubscription = false
                return
            }
            if (pendingSensorCharSubscription) {
                pendingSensorCharSubscription = false
                val service = gatt.getService(GattProfile.EI_SERVICE_UUID) ?: return
                val sensorChar = service.getCharacteristic(GattProfile.SENSOR_CHAR_UUID)
                if (sensorChar != null) {
                    Log.i(TAG, "Enabling sensor data notifications")
                    enableNotification(gatt, sensorChar)
                }
            }
        }

        // API < 33 callback (still called on older devices)
        @Deprecated("Replaced by onCharacteristicChanged with value param (API 33+)")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicData(characteristic.uuid, characteristic.value)
        }

        // API 33+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(characteristic.uuid, value)
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Helpers                                                                  */
    /* ---------------------------------------------------------------------- */

    @SuppressLint("MissingPermission")
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(GattProfile.CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun handleCharacteristicData(uuid: java.util.UUID, data: ByteArray) {
        when (uuid) {
            GattProfile.INFERENCE_CHAR_UUID -> parseInferenceResult(data)
            GattProfile.SENSOR_CHAR_UUID    -> parseSensorData(data)
        }
    }

    private fun parseInferenceResult(data: ByteArray) {
        if (data.size < INFERENCE_RESULT_SIZE) {
            Log.w(TAG, "Inference data too short: ${data.size} bytes (need $INFERENCE_RESULT_SIZE)")
            return
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val labelBytes = ByteArray(32)
        buf.get(labelBytes)
        val label = String(labelBytes, Charsets.UTF_8).trimEnd('\u0000')
        val confidence    = buf.float        // offset 32
        val dspTimeMs     = buf.int          // offset 36
        val classTimeMs   = buf.int          // offset 40
        val zephyrTs      = buf.long         // offset 44

        val result = ZephyrInferenceResult(label, confidence, dspTimeMs, classTimeMs, zephyrTs)
        Log.i(TAG, "Inference: \"$label\" (${(confidence * 100).toInt()}%)")
        _latestInference.value = result

        coroutineScope.launch {
            dataRepository.saveZephyrInferenceResult(result)
        }
    }

    private fun parseSensorData(data: ByteArray) {
        if (data.size < 4 || data.size % 4 != 0) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(data.size / 4) { buf.float }
        coroutineScope.launch {
            dataRepository.saveZephyrSensorData(floats)
        }
    }
}
