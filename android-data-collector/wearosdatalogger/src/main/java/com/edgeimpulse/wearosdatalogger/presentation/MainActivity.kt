/* The Clear BSD License
 *
 * Copyright (c) 2026 EdgeImpulse Inc.
 * All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.edgeimpulse.wearosdatalogger.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.edgeimpulse.wearosdatalogger.WearSensorBus
import com.edgeimpulse.wearosdatalogger.presentation.theme.GATTSensors2Theme

/** Preset labels the wearer can cycle through with the on-device chip. */
private val LABEL_PRESETS = listOf(
    "idle", "walk", "run", "stairs_up", "stairs_down", "sit", "stand",
)

/**
 * Watch launcher. The phone drives capture via the `/wear/cmd` message
 * path (handled by `PhoneCommandListenerService`); this screen mirrors
 * [WearSensorBus] state for quick visual feedback and offers a manual
 * fallback start/stop button for debugging without the phone.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        WearSensorBus.init(applicationContext)
        setContent { WearApp(applicationContext) }
    }
}

@Composable
fun WearApp(ctx: android.content.Context) {
    val running by WearSensorBus.running.collectAsState()
    val busLabel by WearSensorBus.label.collectAsState()
    val sent    by WearSensorBus.sentCount.collectAsState()
    val previewSensor by WearSensorBus.previewSensor.collectAsState()
    val previewSamples by WearSensorBus.previewSamples.collectAsState()
    val availableSensors by WearSensorBus.availableSensors.collectAsState()

    // Local pick survives recompose; while a capture is running we show
    // the bus label (which may have been set by the phone).
    var pickIdx by remember { mutableStateOf(0) }
    val pickedLabel = LABEL_PRESETS[pickIdx]
    val displayLabel = if (running) busLabel else pickedLabel

    val toggleRecord: () -> Unit = {
        if (running) WearSensorBus.stop()
        else WearSensorBus.start(ctx, pickedLabel, 0L)
    }

    GATTSensors2Theme {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colors.background)
                // Tap anywhere outside an interactive child (chips/button)
                // to toggle recording — makes the UI feel alive even before
                // the wearer notices the Start button.
                .clickable(onClick = toggleRecord),
            contentAlignment = Alignment.Center,
        ) {
            TimeText()
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = if (running) "● Recording $previewSensor" else "Idle",
                    color = if (running) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.title3,
                )
                Waveform(
                    samples = previewSamples,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
                Chip(
                    onClick = { nextSensor(availableSensors, previewSensor)?.let(WearSensorBus::setPreviewSensor) },
                    enabled = availableSensors.size > 1,
                    label = { Text("sensor: $previewSensor") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                Chip(
                    onClick = {
                        if (!running) pickIdx = (pickIdx + 1) % LABEL_PRESETS.size
                    },
                    enabled = !running,
                    label = { Text("label: $displayLabel") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                Text("sent: $sent", color = MaterialTheme.colors.onBackground)
                Button(
                    onClick = toggleRecord,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (running) "■ Stop" else "● Record")
                }
                Text(
                    text = if (running) "tap anywhere to stop" else "tap anywhere to record",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.caption3,
                )
            }
        }
    }
}

private fun nextSensor(available: List<String>, current: String): String? {
    if (available.isEmpty()) return null
    val i = available.indexOf(current)
    return available[(i + 1).mod(available.size)]
}

@Composable
private fun Waveform(samples: FloatArray, color: Color, modifier: Modifier = Modifier) {
    val axis = MaterialTheme.colors.onBackground.copy(alpha = 0.25f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        drawLine(axis, Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)
        if (samples.size < 2) return@Canvas
        var lo = samples[0]; var hi = samples[0]
        for (v in samples) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        val stepX = w / (samples.size - 1).toFloat()
        val path = Path()
        for (i in samples.indices) {
            val x = i * stepX
            val norm = (samples[i] - lo) / range
            val y = h - norm * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}
