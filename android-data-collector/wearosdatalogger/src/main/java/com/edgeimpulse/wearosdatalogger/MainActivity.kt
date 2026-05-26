package com.edgeimpulse.wearosdatalogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Watch UI is intentionally minimal — the phone drives capture via the
 * `/wear/cmd` message path. This screen lets the wearer kick off a
 * standalone capture for debugging without needing the phone.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearSensorBus.init(applicationContext)
        setContent { WearApp(applicationContext) }
    }
}

@Composable
fun WearApp(ctx: android.content.Context) {
    val running by WearSensorBus.running.collectAsState()
    val label   by WearSensorBus.label.collectAsState()
    val sent    by WearSensorBus.sentCount.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text(if (running) "Recording: $label" else "Idle")
        Text("Samples sent: $sent")
        Button(onClick = {
            if (running) WearSensorBus.stop()
            else WearSensorBus.start(ctx, label.ifBlank { "idle" }, 0L)
        }) {
            Text(if (running) "Stop" else "Manual start")
        }
    }
}
