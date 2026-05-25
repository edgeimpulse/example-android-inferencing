/* The Clear BSD License
 *
 * Copyright (c) 2026 EdgeImpulse Inc.
 * All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.edgeimpulse.wearosdatalogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "WearSensorBus"
private const val PATH_SAMPLES = "/wear/samples"
private const val FLUSH_INTERVAL_MS = 250L

/**
 * Singleton sensor pump for the watch. Registers every available sensor,
 * buffers samples in memory, and flushes them as line-delimited
 * `<key>|<ts>|v0,v1,…` batches to the connected phone every 250 ms.
 *
 * Activated by [PhoneCommandListenerService] on receipt of `/wear/cmd start`.
 */
object WearSensorBus : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var messageClient: com.google.android.gms.wearable.MessageClient? = null
    private var nodeClient:    com.google.android.gms.wearable.NodeClient?    = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var flushJob: Job? = null
    private var stopJob:  Job? = null

    private val buffer = StringBuilder()
    private val bufferLock = Any()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _label = MutableStateFlow("idle")
    val label = _label.asStateFlow()

    private val _sentCount = MutableStateFlow(0)
    val sentCount = _sentCount.asStateFlow()

    private val sensorKey: Map<Int, String> = mapOf(
        Sensor.TYPE_ACCELEROMETER       to "accel",
        Sensor.TYPE_GYROSCOPE           to "gyro",
        Sensor.TYPE_MAGNETIC_FIELD      to "mag",
        Sensor.TYPE_LINEAR_ACCELERATION to "linear_accel",
        Sensor.TYPE_GRAVITY             to "gravity",
        Sensor.TYPE_ROTATION_VECTOR     to "rotation",
        Sensor.TYPE_HEART_RATE          to "hr",
        Sensor.TYPE_LIGHT               to "light",
        Sensor.TYPE_PRESSURE            to "pressure",
        Sensor.TYPE_PROXIMITY           to "prox",
    )

    fun init(ctx: Context) {
        if (sensorManager == null) {
            sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            messageClient = Wearable.getMessageClient(ctx)
            nodeClient    = Wearable.getNodeClient(ctx)
        }
    }

    fun start(ctx: Context, newLabel: String, durationMs: Long) {
        init(ctx)
        if (_running.value) return
        _label.value = newLabel
        _sentCount.value = 0
        synchronized(bufferLock) { buffer.clear() }

        val sm = sensorManager ?: return
        sensorKey.keys.forEach { type ->
            sm.getDefaultSensor(type)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        _running.value = true

        flushJob = scope.launch {
            while (_running.value) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
            // Final flush after stop.
            flush()
        }
        if (durationMs > 0) {
            stopJob = scope.launch {
                delay(durationMs)
                stop()
            }
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        sensorManager?.unregisterListener(this)
        stopJob?.cancel(); stopJob = null
        // flushJob exits on next loop iteration.
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = sensorKey[event.sensor.type] ?: return
        val ts  = System.currentTimeMillis()
        val sb  = StringBuilder().apply {
            append(key); append('|'); append(ts); append('|')
            for ((i, v) in event.values.withIndex()) {
                if (i > 0) append(',')
                append(v)
            }
            append('\n')
        }
        synchronized(bufferLock) { buffer.append(sb) }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    private suspend fun flush() {
        val payload: String = synchronized(bufferLock) {
            if (buffer.isEmpty()) return
            val s = buffer.toString(); buffer.clear(); s
        }
        try {
            val nodes = nodeClient?.connectedNodes?.await().orEmpty()
            if (nodes.isEmpty()) return
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val client = messageClient ?: return
            nodes.forEach { n ->
                client.sendMessage(n.id, PATH_SAMPLES, bytes).await()
            }
            _sentCount.value = _sentCount.value + payload.count { it == '\n' }
        } catch (e: Exception) {
            Log.w(TAG, "flush failed: ${e.message}")
        }
    }
}
