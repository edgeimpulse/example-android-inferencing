package com.edgeimpulse.gattsensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class SensorCollectorTest {

    private lateinit var sensorCollector: SensorCollector
    private val context: Context = mock()
    private val repository: DataRepository = mock()
    private val gattServerManager: GattServerManager = mock()
    private val sensorManager: SensorManager = mock()
    private val accelerometer: Sensor = mock()

    @Before
    fun setup() {
        whenever(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accelerometer)
        sensorCollector = SensorCollector(context, repository, gattServerManager)
    }

    @Test
    fun `onSensorChanged emits data and saves to repository`() = runTest {
        // Given
        val sensorEvent: SensorEvent = mock()
        val sensor: Sensor = mock()
        whenever(sensor.type).thenReturn(Sensor.TYPE_ACCELEROMETER)
        whenever(sensorEvent.sensor).thenReturn(sensor)
        val values = floatArrayOf(1.0f, 2.0f, 3.0f)
        val field = SensorEvent::class.java.getDeclaredField("values")
        field.isAccessible = true
        field.set(sensorEvent, values)

        // When
        sensorCollector.onSensorChanged(sensorEvent)

        // Then
        val emittedData = sensorCollector.dataFlow.first()
        assert(emittedData.values["accelX"] == 1.0f)
        assert(emittedData.values["accelY"] == 2.0f)
        assert(emittedData.values["accelZ"] == 3.0f)
        verify(repository).saveSensorData(any())
    }
}
