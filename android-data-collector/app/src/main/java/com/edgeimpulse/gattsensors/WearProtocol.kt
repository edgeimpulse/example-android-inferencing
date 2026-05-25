/* The Clear BSD License
 *
 * Copyright (c) 2026 EdgeImpulse Inc.
 * All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.edgeimpulse.gattsensors

import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Wire-format constants and helpers shared between the phone app and the
 * Wear OS companion. Kept tiny on purpose — strings are easy to debug over
 * `adb logcat` and avoid pulling in a serialiser on the watch.
 */
object WearProtocol {
    /** Legacy single-sensor accel CSV path ("x,y,z"). Still honoured. */
    const val PATH_LEGACY_ACCEL = "/sensor_data"

    /** New multi-sensor batch path. Payload format (UTF-8):
     *    `<sensorKey>|<ts_ms>|v0,v1,v2`
     *  One sample per line. Multiple lines per message allowed.
     *
     *  `sensorKey` is one of: `accel`, `gyro`, `mag`, `linear_accel`,
     *  `gravity`, `rotation`, `hr`, `light`, `pressure`, `prox`.
     */
    const val PATH_SAMPLES = "/wear/samples"

    /** Phone → watch control. Payload: `start|<label>|<durationMs>` or `stop`. */
    const val PATH_CMD     = "/wear/cmd"

    /** Phone → watch capability advertisement (heartbeat). Payload empty. */
    const val PATH_PING    = "/wear/ping"

    fun encodeStart(label: String, durationMs: Long) = "start|$label|$durationMs"
    const val CMD_STOP = "stop"
}

/**
 * Process-wide bus for forwarding Wearable messages from the receiving
 * service to the [DataRepository] held by the active ViewModel. The
 * `WearableListenerService` cannot share instances directly with the
 * `Activity` process objects so we route through a SharedFlow instead.
 */
object WearEventBus {
    private val _events = MutableSharedFlow<MessageEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()
    fun publish(event: MessageEvent) {
        _events.tryEmit(event)
    }
}
