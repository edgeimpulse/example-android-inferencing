package com.edgeimpulse.gattsensors

import java.util.UUID

/**
 * UUIDs that match the Zephyr ei-zephyr-ble-gatt-client firmware exactly.
 *
 * Service:   12345678-1234-5678-1234-56789abcdef0
 * Inference: 12345678-1234-5678-1234-56789abcdef1  (READ + NOTIFY)
 * Sensor:    12345678-1234-5678-1234-56789abcdef2  (READ + NOTIFY)
 * State:     12345678-1234-5678-1234-56789abcdef3  (READ + WRITE)
 */
object GattProfile {
    val EI_SERVICE_UUID:    UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    val INFERENCE_CHAR_UUID:UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    val SENSOR_CHAR_UUID:   UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    val STATE_CHAR_UUID:    UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")

    /** Standard Bluetooth CCCD descriptor UUID */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Legacy: keep for GattServerManager (phone advertises the same service outward)
    val SERVICE_UUID: UUID = EI_SERVICE_UUID
    val ACCELEROMETER_CHARACTERISTIC_UUID: UUID = SENSOR_CHAR_UUID
}
