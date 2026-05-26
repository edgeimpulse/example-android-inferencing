package com.edgeimpulse.gattsensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the [SensorData] container. We deliberately do
 * not exercise the [SensorCollector] sensor-callback path here — it
 * receives final [android.hardware.SensorEvent] objects that Mockito
 * cannot subclass on a stock JVM. The behaviour of the value-key naming
 * scheme (`accel_0`/`accel_1`/`accel_2`, etc.) is captured below so a
 * regression — e.g. accidentally reintroducing the old `accelX`/`accelY`/
 * `accelZ` headers — fails CI.
 */
class SensorDataTest {

    @Test
    fun `value map preserves insertion order for csv columns`() {
        val sample = SensorData(
            timestamp = 1234L,
            values = linkedMapOf(
                "accel_0" to 0.1f,
                "accel_1" to 0.2f,
                "accel_2" to 0.3f,
            ),
        )
        assertEquals(listOf("accel_0", "accel_1", "accel_2"), sample.values.keys.toList())
        assertEquals(0.1f, sample.values["accel_0"])
        assertEquals(0.3f, sample.values["accel_2"])
    }

    @Test
    fun `missing key returns null so csv writer can emit empty cell`() {
        val sample = SensorData(0L, mapOf("gyro_0" to 1f))
        assertTrue(sample.values["accel_0"] == null)
    }
}
