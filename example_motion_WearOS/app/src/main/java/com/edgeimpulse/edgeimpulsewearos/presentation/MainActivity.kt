package com.edgeimpulse.edgeimpulsewearos.presentation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.*
import com.edgeimpulse.edgeimpulsewearos.presentation.theme.EdgeImpulseWearOSTheme

/**
 * If your model needs e.g. 120 float values (40 sets of x,y,z), set this to 120.
 */
private const val EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE = 375

class MainActivity : ComponentActivity(), SensorEventListener {

    companion object {
        init {
            // The name must match add_library(...) in CMakeLists.txt
            System.loadLibrary("edgeimpulsewearos")
        }
    }

    // JNI function that runs inference using the Edge Impulse SDK, returning a String
    external fun runInference(data: FloatArray): String?

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Uncomment to add Gyroscope support
    // private var gyroscope: Sensor? = null

    // Uncomment to add Heart Rate sensor support
    //private var heartRateSensor: Sensor? = null

    // Collect sensor data in this ring buffer
    private val ringBuffer = FloatArray(EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE)
    private var ringBufferIndex = 0

    // State to store the most recent inference result
    private val _inferenceResult = mutableStateOf("Collecting data...")
    val inferenceResult: State<String> get() = _inferenceResult

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Initialize the sensor manager and accelerometer
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Uncomment to initialize Gyroscope
        // gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Uncomment to initialize Heart Rate Sensor
        // heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        // Set up our Wear Compose UI
        setContent {
            EdgeImpulseWearOSTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    TimeText()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        InferenceChip(inferenceText = inferenceResult.value)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        // Uncomment to register Gyroscope
        /*
        gyroscope?.also {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        */

        // Uncomment to register Heart Rate Sensor
        /*
        heartRateSensor?.also {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        */
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ringBuffer[ringBufferIndex++] = event.values[0] // X
                ringBuffer[ringBufferIndex++] = event.values[1] // Y
                ringBuffer[ringBufferIndex++] = event.values[2] // Z
            }

            // Uncomment to add Gyroscope readings
            /*
            Sensor.TYPE_GYROSCOPE -> {
                ringBuffer[ringBufferIndex++] = event.values[0] // X rotation
                ringBuffer[ringBufferIndex++] = event.values[1] // Y rotation
                ringBuffer[ringBufferIndex++] = event.values[2] // Z rotation
            }
            */

            // Uncomment to add Heart Rate readings
            /*
            Sensor.TYPE_HEART_RATE -> {
                ringBuffer[ringBufferIndex++] = event.values[0] // Heart rate BPM
            }
            */
        }

        // If buffer is full, run inference
        if (ringBufferIndex >= EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
            ringBufferIndex = 0
            val result = runInference(ringBuffer)
            _inferenceResult.value = result ?: "Inference returned null"
            Log.d("EdgeImpulse", "Inference result: ${_inferenceResult.value}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}

/**
 * Displays the inference result on a Chip.
 */
@Composable
fun InferenceChip(inferenceText: String) {
    Chip(
        onClick = { /* No action here, just displaying text */ },
        label = {
            Text(
                text = inferenceText,
                style = MaterialTheme.typography.body1
            )
        },
        colors = ChipDefaults.primaryChipColors()
    )
}
