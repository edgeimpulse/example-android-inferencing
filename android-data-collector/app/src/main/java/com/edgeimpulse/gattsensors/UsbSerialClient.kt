package com.edgeimpulse.gattsensors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "UsbSerialClient"
private const val ACTION_USB_PERMISSION = "com.edgeimpulse.gattsensors.USB_PERMISSION"
private const val BAUD_RATE = 115200

/**
 * Manages a USB OTG serial connection to any CDC-ACM / FTDI / CP21xx / CH34x
 * device (e.g. an Arduino) that sends sensor data in the expected text format.
 *
 * ## Wire protocol
 *
 * All lines are ASCII, terminated by `\n` (or `\r\n`).
 *
 * | Prefix | Meaning | Example |
 * |--------|---------|---------|
 * | `!`    | Column header (sent once after boot) | `!ax,ay,az` |
 * | `#`    | Comment – ignored | `# starting up` |
 * | none   | Data row – comma-separated floats | `0.12,-0.34,9.81` |
 *
 * If no `!` header line is ever received the columns are named
 * `col_0`, `col_1`, … automatically.
 */
class UsbSerialClient(
    private val context: Context,
    private val dataRepository: DataRepository,
) : SerialInputOutputManager.Listener {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor   = Executors.newSingleThreadExecutor()
    private val scope      = CoroutineScope(Dispatchers.IO)

    private val _isConnected    = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _statusMessage  = MutableStateFlow("No USB device attached")
    val statusMessage = _statusMessage.asStateFlow()

    private val _sampleCount    = MutableStateFlow(0)
    val sampleCount = _sampleCount.asStateFlow()

    /** Column names from the `!header` line, e.g. ["ax","ay","az"]. */
    private val _columnHeaders  = MutableStateFlow<List<String>>(emptyList())
    val columnHeaders = _columnHeaders.asStateFlow()

    /** Most-recent parsed sample — one Float per column. */
    private val _lastSample     = MutableStateFlow<FloatArray?>(null)
    val lastSample = _lastSample.asStateFlow()

    private var ioManager: SerialInputOutputManager? = null
    private val lineBuffer = StringBuilder()

    // -------------------------------------------------------------------------
    // USB permission / attach / detach broadcast receiver
    // -------------------------------------------------------------------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openPort(it) }
                    } else {
                        _statusMessage.value = "USB permission denied"
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached")
                    tryConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    close()
                    _statusMessage.value = "USB device disconnected"
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scan for any attached serial device and open it. If the OS hasn't
     * granted USB permission yet, a system dialog is shown and the port
     * opens automatically when permission is granted.
     */
    fun tryConnect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            _statusMessage.value = "No compatible USB serial device found"
            return
        }
        val driver = drivers.first()
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            openPort(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
            _statusMessage.value = "Requesting USB permission…"
        }
    }

    /** Disconnect and release the serial port. */
    fun close() {
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null
        _isConnected.value = false
    }

    /** Reset the running sample counter (e.g. at the start of a recording window). */
    fun resetSampleCount() { _sampleCount.value = 0 }

    /** Call from the owning component's onDestroy to avoid receiver leaks. */
    fun unregister() {
        close()
        try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Internal — open serial port
    // -------------------------------------------------------------------------

    private fun openPort(device: UsbDevice) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device.deviceId == device.deviceId } ?: run {
            _statusMessage.value = "No driver found for ${device.productName ?: device.deviceName}"
            return
        }
        val connection = usbManager.openDevice(device) ?: run {
            _statusMessage.value = "Failed to open USB connection — check permission"
            return
        }
        val port = driver.ports.firstOrNull() ?: run {
            _statusMessage.value = "Device has no serial ports"
            return
        }
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            lineBuffer.clear()
            _isConnected.value = true
            _statusMessage.value = "Connected: ${device.productName ?: device.deviceName}"
            Log.i(TAG, "Serial port opened @ $BAUD_RATE baud (${device.productName})")
            val mgr = SerialInputOutputManager(port, this)
            ioManager = mgr
            executor.submit(mgr)
        } catch (e: Exception) {
            Log.e(TAG, "openPort failed", e)
            _statusMessage.value = "Failed to open port: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // SerialInputOutputManager.Listener
    // -------------------------------------------------------------------------

    override fun onNewData(data: ByteArray) {
        for (b in data) {
            val ch = b.toInt().toChar()
            when {
                ch == '\n' -> {
                    processLine(lineBuffer.toString().trimEnd('\r'))
                    lineBuffer.clear()
                }
                ch != '\r' -> {
                    lineBuffer.append(ch)
                    // Guard against a misbehaving device flooding with no newlines
                    if (lineBuffer.length > 1024) lineBuffer.clear()
                }
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial IO error", e)
        _isConnected.value = false
        _statusMessage.value = "Serial error: ${e.message}"
    }

    // -------------------------------------------------------------------------
    // Line parsing
    // -------------------------------------------------------------------------

    private fun processLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return

        // Header declaration: !ax,ay,az[,gx,gy,gz,...]
        if (trimmed.startsWith("!")) {
            val cols = trimmed.removePrefix("!")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (cols.isNotEmpty()) {
                _columnHeaders.value = cols
                Log.i(TAG, "USB serial headers: $cols")
            }
            return
        }

        // Data row: comma-separated floats
        val parts = trimmed.split(',')
        val floats = parts.mapNotNull { it.trim().toFloatOrNull() }
        if (floats.isEmpty()) return

        // Auto-generate column names if we haven't seen a header yet
        if (_columnHeaders.value.size != floats.size) {
            _columnHeaders.value = List(floats.size) { i -> "col_$i" }
        }

        val arr = floats.toFloatArray()
        _lastSample.value = arr
        _sampleCount.value = _sampleCount.value + 1
        scope.launch { dataRepository.saveUsbSensorData(arr) }
    }
}
