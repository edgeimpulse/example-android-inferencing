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
 * Binary layout (matches inference_result_t in gatt_client.h, declared
 * with __attribute__((packed)) on the firmware side so timestamp sits at
 * offset 44 with no padding on either ARM or RISC-V):
 *   offset  0 : char label[32]
 *   offset 32 : float confidence
 *   offset 36 : uint32_t dsp_time_ms
 *   offset 40 : uint32_t classification_time_ms
 *   offset 44 : uint64_t timestamp
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

    /** Current capture label on the Nesso ("idle", "circle", "updown"). */
    private val _currentLabel = MutableStateFlow("idle")
    val currentLabel = _currentLabel.asStateFlow()

    /** Total raw IMU samples received from the Nesso during the most recent
     *  recording window. Reset by [DataRepository.startZephyrRecording]. */
    private val _sampleCount = MutableStateFlow(0)
    val sampleCount = _sampleCount.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val scannedDevicesLock = Any()

    // Pending: subscribe to sensor char after inference CCC write completes
    private var pendingSensorCharSubscription = false
    // Pending: subscribe to state char after sensor CCC write completes
    private var pendingStateCharSubscription = false

    /* ---------------------------------------------------------------------- */
    /* Scanning                                                                 */
    /* ---------------------------------------------------------------------- */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Device name may live in the scan record OR be cached on the
            // BluetoothDevice; fall back to "(unnamed)" so the entry still
            // shows up in the UI even when the peripheral omits a name.
            val name = result.scanRecord?.deviceName
                ?: result.device.name
                ?: "(unnamed)"
            val bleDevice = BLEDevice(result.device, name, result.device.address, result.rssi)

            // Accumulate for the UI list. BLE scan callbacks can arrive on different
            // threads, so the read-modify-write must be guarded.
            synchronized(scannedDevicesLock) {
                val current = _scannedDevices.value.toMutableList()
                if (current.none { it.address == bleDevice.address }) {
                    current.add(bleDevice)
                    _scannedDevices.value = current
                    Log.d(TAG, "Discovered ${bleDevice.address} \"$name\" rssi=${result.rssi}")
                }
            }

            // Auto-connect when we see the EI service UUID (more reliable than
            // matching on the advertised name, which may not always be present).
            val advertisesEiService = result.scanRecord
                ?.serviceUuids
                ?.any { it.uuid == GattProfile.EI_SERVICE_UUID } == true

            if ((advertisesEiService || name == TARGET_DEVICE_NAME) &&
                !_isConnected.value && bluetoothGatt == null) {
                Log.i(TAG, "Found EI peripheral ($name) — connecting…")
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
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner unavailable — is Bluetooth turned on?")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Filter on the EI service UUID (always advertised by the firmware)
        // rather than the device name (which can be missing from the AD packet
        // when scan-response data isn't included).
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(GattProfile.EI_SERVICE_UUID))
                .build()
        )
        scanner.startScan(filters, settings, scanCallback)
        Log.i(TAG, "Scanning for EI service ${GattProfile.EI_SERVICE_UUID}…")
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
                    Log.i(TAG, "Connected — requesting MTU…")
                    _isConnected.value = true
                    // Larger MTU is required for 6-axis IMU notifications (24 bytes
                    // plus headers exceeds the default 23-byte MTU on some links).
                    gatt.requestMtu(247)
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
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU = $mtu (status=$status) — discovering services…")
            gatt.discoverServices()
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
                pendingSensorCharSubscription = true  // sensor CCC after inference CCC write
                // state CCC + read happens after sensor CCC write
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
                // Reset pending flags so we don't get stuck waiting for a callback that
                // will never arrive with success.
                pendingSensorCharSubscription = false
                pendingStateCharSubscription = false
                return
            }
            if (pendingSensorCharSubscription) {
                pendingSensorCharSubscription = false
                val service = gatt.getService(GattProfile.EI_SERVICE_UUID) ?: return
                val sensorChar = service.getCharacteristic(GattProfile.SENSOR_CHAR_UUID)
                if (sensorChar != null) {
                    Log.i(TAG, "Enabling sensor data notifications")
                    enableNotification(gatt, sensorChar)
                    pendingStateCharSubscription = true
                }
                return
            }
            if (pendingStateCharSubscription) {
                pendingStateCharSubscription = false
                val service = gatt.getService(GattProfile.EI_SERVICE_UUID) ?: return
                val stateChar = service.getCharacteristic(GattProfile.STATE_CHAR_UUID)
                if (stateChar != null) {
                    Log.i(TAG, "Enabling state notifications and reading current label")
                    enableNotification(gatt, stateChar)
                    gatt.readCharacteristic(stateChar)
                }
            }
        }

        @Deprecated("Replaced by onCharacteristicRead with value param (API 33+)")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                handleCharacteristicData(characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicData(characteristic.uuid, value)
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
            GattProfile.STATE_CHAR_UUID     -> parseLabel(data)
        }
    }

    private fun parseLabel(data: ByteArray) {
        val label = String(data, Charsets.UTF_8).trimEnd('\u0000').trim()
        if (label.isNotEmpty() && label != _currentLabel.value) {
            Log.i(TAG, "Label → \"$label\"")
            _currentLabel.value = label
        }
    }

    /**
     * Write a new capture label to the Nesso. Triggers a STATE notification
     * which updates [currentLabel] flow when the firmware echoes it back.
     */
    @SuppressLint("MissingPermission")
    fun setLabel(label: String) {
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "setLabel('$label') ignored — no GATT connection")
            return
        }
        val service = gatt.getService(GattProfile.EI_SERVICE_UUID) ?: return
        val stateChar = service.getCharacteristic(GattProfile.STATE_CHAR_UUID) ?: return
        val bytes = label.toByteArray(Charsets.UTF_8)
        // Optimistic local update so the UI reacts instantly; the firmware
        // echo will overwrite it (no-op if same string).
        _currentLabel.value = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                stateChar, bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            stateChar.value = bytes
            @Suppress("DEPRECATION")
            stateChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(stateChar)
        }
    }

    /** Reset the running sample counter (e.g. at the start of a recording). */
    fun resetSampleCount() { _sampleCount.value = 0 }

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
        _sampleCount.value = _sampleCount.value + 1
        coroutineScope.launch {
            dataRepository.saveZephyrSensorData(floats)
        }
    }
}
