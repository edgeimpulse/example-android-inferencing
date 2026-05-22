package com.edgeimpulse.wearosdatalogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var messageClient: MessageClient

    private var isLogging by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        messageClient = Wearable.getMessageClient(this)

        setContent {
            WearApp(isLogging, onToggle = {
                isLogging = !isLogging
                if (isLogging) {
                    startSensor()
                } else {
                    stopSensor()
                }
            })
        }
    }

    private fun startSensor() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val values = event.values.joinToString(",")
            sendMessage(values)
        }
    }

    private fun sendMessage(data: String) {
        // In a real app, you would want to find the connected node more dynamically
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.firstOrNull()?.id?.let {
                messageClient.sendMessage(it, "/sensor_data", data.toByteArray())
                    .addOnSuccessListener { Log.d("WearOS", "Message sent!") }
                    .addOnFailureListener { Log.e("WearOS", "Message failed!", it) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Not used */ }
}

@Composable
fun WearApp(isLogging: Boolean, onToggle: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onToggle) {
            Text(if (isLogging) "Stop Logging" else "Start Logging")
        }
    }
}
