/* The Clear BSD License
 *
 * Copyright (c) 2026 EdgeImpulse Inc.
 * All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.edgeimpulse.wearosdatalogger

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

private const val TAG = "PhoneCmd"
private const val PATH_CMD = "/wear/cmd"

/**
 * Receives `/wear/cmd` from the paired phone and drives [WearSensorBus].
 * Payload: `start|<label>|<durationMs>` or `stop`.
 */
class PhoneCommandListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_CMD) return
        val msg = String(event.data, Charsets.UTF_8)
        Log.d(TAG, "cmd: $msg")
        val parts = msg.split('|')
        when (parts.firstOrNull()) {
            "start" -> {
                val label = parts.getOrNull(1) ?: "idle"
                val dur   = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                WearSensorBus.start(applicationContext, label, dur)
            }
            "stop" -> WearSensorBus.stop()
        }
    }
}
