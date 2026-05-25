/* The Clear BSD License
 *
 * Copyright (c) 2026 EdgeImpulse Inc.
 * All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.edgeimpulse.gattsensors

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "WearOSClient"

/**
 * Phone-side helper: discovers paired Wear OS nodes and pushes start/stop
 * commands. Sample frames flow the other way and land in
 * [WearableMessageListenerService] -> [WearEventBus] -> [DataRepository].
 */
class WearOSClient(private val context: Context) {

    private val nodeClient    = Wearable.getNodeClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val scope         = CoroutineScope(Dispatchers.IO)

    private val _connectedNode = MutableStateFlow<String?>(null)
    /** Display name of the first connected wear node, or null if none. */
    val connectedNode = _connectedNode.asStateFlow()

    private val _samplesReceived = MutableStateFlow(0)
    val samplesReceived = _samplesReceived.asStateFlow()

    init {
        // Push samplesReceived from the bus so the UI updates live.
        scope.launch {
            WearEventBus.events.collect { ev ->
                if (ev.path == WearProtocol.PATH_SAMPLES) {
                    val lines = String(ev.data).count { it == '\n' } + 1
                    _samplesReceived.value = _samplesReceived.value + lines
                }
            }
        }
    }

    fun resetCount() { _samplesReceived.value = 0 }

    /** Probe the data layer for a connected node. Updates [connectedNode]. */
    fun refreshNodes() {
        scope.launch {
            try {
                val nodes: List<Node> = nodeClient.connectedNodes.await()
                _connectedNode.value = nodes.firstOrNull()?.displayName
            } catch (e: Exception) {
                Log.w(TAG, "Node refresh failed: ${e.message}")
                _connectedNode.value = null
            }
        }
    }

    fun startRecording(label: String, durationMs: Long) =
        send(WearProtocol.PATH_CMD, WearProtocol.encodeStart(label, durationMs))

    fun stopRecording() = send(WearProtocol.PATH_CMD, WearProtocol.CMD_STOP)

    private fun send(path: String, payload: String) {
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No wear nodes connected — cannot send $path")
                    return@launch
                }
                _connectedNode.value = nodes.first().displayName
                val bytes = payload.toByteArray(Charsets.UTF_8)
                nodes.forEach { n ->
                    messageClient.sendMessage(n.id, path, bytes).await()
                }
            } catch (e: Exception) {
                Log.e(TAG, "send($path) failed: ${e.message}")
            }
        }
    }
}
