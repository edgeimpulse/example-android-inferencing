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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.edgeimpulse.wearosdatalogger.WearSensorBus
import com.edgeimpulse.wearosdatalogger.presentation.theme.GATTSensors2Theme

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
    val label   by WearSensorBus.label.collectAsState()
    val sent    by WearSensorBus.sentCount.collectAsState()

    GATTSensors2Theme {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            TimeText()
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = if (running) "Recording" else "Idle",
                    color = MaterialTheme.colors.primary,
                )
                Text("label: $label", color = MaterialTheme.colors.onBackground)
                Text("sent: $sent", color = MaterialTheme.colors.onBackground)
                Button(onClick = {
                    if (running) WearSensorBus.stop()
                    else WearSensorBus.start(ctx, label.ifBlank { "idle" }, 0L)
                }) {
                    Text(if (running) "Stop" else "Manual")
                }
            }
        }
    }
}
