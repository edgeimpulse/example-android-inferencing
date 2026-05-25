package com.edgeimpulse.gattsensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class SensorData(
    val timestamp: Long,
    val values: Map<String, Float>
)

class SensorCollector(
    private val context: Context,
    private val repository: DataRepository,
    private val gattServerManager: GattServerManager
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Map every available phone sensor to a stable canonical key (matches
     * the keys used in [WearProtocol] so the same EI labels apply on the
     * watch and phone). Only sensors the device actually exposes are kept.
     */
    private val allSensors: List<Pair<String, Sensor>> = buildList {
        fun add(key: String, type: Int) {
            sensorManager.getDefaultSensor(type)?.let { add(key to it) }
        }
        add("accel",        Sensor.TYPE_ACCELEROMETER)
        add("gyro",         Sensor.TYPE_GYROSCOPE)
        add("mag",          Sensor.TYPE_MAGNETIC_FIELD)
        add("linear_accel", Sensor.TYPE_LINEAR_ACCELERATION)
        add("gravity",      Sensor.TYPE_GRAVITY)
        add("rotation",     Sensor.TYPE_ROTATION_VECTOR)
        add("hr",           Sensor.TYPE_HEART_RATE)
        add("light",        Sensor.TYPE_LIGHT)
        add("pressure",     Sensor.TYPE_PRESSURE)
        add("prox",         Sensor.TYPE_PROXIMITY)
    }

    private val sensorKeyByType: Map<Int, String> =
        allSensors.associate { (k, s) -> s.type to k }

    /**
     * UI-label → canonical key map. Drives the Collect-tab dropdown so any
     * available IMU / environmental sensor can be captured as its own EI
     * sample stream (just like the legacy Accelerometer / PPG modes).
     */
    private val labelToKey: Map<String, String> = mapOf(
        "Accelerometer"        to "accel",
        "Gyroscope"            to "gyro",
        "Magnetometer"         to "mag",
        "Linear Acceleration"  to "linear_accel",
        "Gravity"              to "gravity",
        "Rotation Vector"      to "rotation",
        "PPG (Heart Rate)"     to "hr",
        "Light"                to "light",
        "Pressure (Barometer)" to "pressure",
        "Proximity"            to "prox",
    )

    /** UI labels for every sensor this device actually exposes. */
    fun availableSensorLabels(): List<String> {
        val have = allSensors.map { it.first }.toSet()
        return labelToKey.filterValues { it in have }.keys.toList()
    }

    private val sensorByKey: Map<String, Sensor> = allSensors.toMap()

    private val _dataFlow = MutableSharedFlow<SensorData>()
    val dataFlow = _dataFlow.asSharedFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /** Single-sensor capture (Collect tab). Accepts any UI label from
     *  [availableSensorLabels]; unknown labels fall back to the accelerometer. */
    fun start(sensorType: String = "Accelerometer") {
        val key = labelToKey[sensorType] ?: "accel"
        val sensor = sensorByKey[key] ?: sensorByKey["accel"] ?: return
        // Faster than SENSOR_DELAY_NORMAL so plotted lines stay smooth
        // without flooding the upload buffer for low-rate environmental sensors.
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /** Register every sensor the device exposes for a multi-modal capture. */
    fun startAll() {
        allSensors.forEach { (_, s) ->
            sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = sensorKeyByType[event.sensor.type]
        // Tag every axis with a per-sensor prefix so multiple streams can
        // share the same chart pane without colliding key names.
        val values: Map<String, Float> = if (key != null) {
            event.values.mapIndexed { i, v -> "${key}_${i}" to v }.toMap()
        } else emptyMap()

        // Feed the multi-modal recorder with a key + raw values copy so
        // every sensor stream is uploaded as its own EI sample.
        if (key != null) {
            val copy = FloatArray(event.values.size) { event.values[it] }
            repository.appendPhoneSample(key, copy)
        }

        if (values.isNotEmpty()) {
            val sample = SensorData(System.currentTimeMillis(), values)
            coroutineScope.launch {
                _dataFlow.emit(sample)
                repository.saveSensorData(sample)
                gattServerManager.notifySensorData(sample)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Not needed for this example */ }
}
