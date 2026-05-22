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
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    private val _dataFlow = MutableSharedFlow<SensorData>()
    val dataFlow = _dataFlow.asSharedFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    fun start(sensorType: String = "Accelerometer") {
        val sensor = when (sensorType) {
            "Accelerometer" -> accelerometer
            "PPG (Heart Rate)" -> heartRateSensor
            else -> null
        }
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> mapOf("accelX" to event.values[0], "accelY" to event.values[1], "accelZ" to event.values[2])
            Sensor.TYPE_HEART_RATE -> mapOf("heartRate" to event.values[0])
            else -> emptyMap()
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
