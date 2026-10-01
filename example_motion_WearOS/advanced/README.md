# Edge Impulse Motion Recognition on WearOS

Real-time motion and activity recognition on WearOS smartwatches using Edge Impulse and TensorFlow Lite. Deploy gesture recognition, activity classification, and fitness tracking entirely on-device.

## Features

- **Real-time motion recognition** using accelerometer (and optional gyroscope/heart rate)
- **WearOS optimized** UI with Compose for Wear
- **On-device inference** with TensorFlow Lite
- **XNNPACK acceleration** for efficient processing
- **Low power consumption** for all-day wearability
- **Ring buffer** for continuous data collection
- **Health & fitness applications**:
  - Activity recognition (walking, running, cycling)
  - Gesture detection
  - Fall detection
  - Exercise form tracking

## What You'll Build

A WearOS app that continuously monitors motion sensor data and performs real-time activity or gesture classification directly on your smartwatch.

## Prerequisites

- [Edge Impulse account](https://edgeimpulse.com/signup)
- Trained **motion/accelerometer** model
- Android Studio (Ladybug 2024.2.2 or later)
- WearOS device or emulator (API 30+)
- Android NDK 27.0.12077973
- CMake 3.22.1

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/edgeimpulse/example-android-inferencing.git
cd example-android-inferencing/example_motion_WearOS
```

### 2. Download TensorFlow Lite Libraries

```bash
cd app/src/main/cpp/tensorflow-lite

# Windows
download_tflite_libs.bat

# macOS/Linux
sh download_tflite_libs.sh
```

### 3. Export Your Model

1. In Edge Impulse Studio, go to **Deployment**
2. Select **Android (C++ library)**
3. Enable **EON Compiler** (optional, recommended)
4. Click **Build** and download the `.zip`

### 4. Integrate the Model

1. Extract the downloaded `.zip` file
2. Copy **all files except `CMakeLists.txt`** to:
   ```
   app/src/main/cpp/
   ```

Your structure should look like:
```
app/src/main/cpp/
├── edge-impulse-sdk/
├── model-parameters/
├── tflite-model/
├── wearosinference.cpp
└── CMakeLists.txt (don't replace this)
```

### 5. Configure Input Size

Check your model's input requirements and update the constant in `MainActivity.kt`:

```kotlin
// If your model needs 375 float values (125 sets of x,y,z), set this to 375
private const val EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE = 375
```

You can find this value in your model's `model_metadata.h`:
```cpp
#define EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE 375
```

### 6. Build and Run

1. Open the project in Android Studio
2. **Build** → **Make Project**
3. Connect your WearOS device or start a WearOS emulator
4. Run the app
5. Perform gestures or activities to see classification results!

## How It Works

### Sensor Setup

The app registers accelerometer (and optionally gyroscope) listeners:

```kotlin
override fun onResume() {
    super.onResume()
    accelerometer?.also {
        sensorManager.registerListener(
            this,
            it,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }
}
```

### Ring Buffer Collection

Sensor data is collected in a ring buffer matching your model's window size:

```kotlin
private val ringBuffer = FloatArray(EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE)
private var ringBufferIndex = 0

override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> {
            ringBuffer[ringBufferIndex++] = event.values[0] // x
            ringBuffer[ringBufferIndex++] = event.values[1] // y
            ringBuffer[ringBufferIndex++] = event.values[2] // z
            
            if (ringBufferIndex >= EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
                ringBufferIndex = 0
                runInference(ringBuffer)
            }
        }
    }
}
```

### Native Inference

Motion data is processed in C++ for optimal performance:

```cpp
extern "C"
JNIEXPORT jstring JNICALL
Java_com_edgeimpulse_edgeimpulsewearos_presentation_MainActivity_runInference(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray data) {
    
    // Convert Java array to C++ vector
    jsize len = env->GetArrayLength(data);
    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    std::vector<float> features(elements, elements + len);
    
    // Create signal
    signal_t signal;
    signal.total_length = features.size();
    signal.get_data = &get_signal_data;
    
    // Run classifier
    ei_impulse_result_t result = {0};
    run_classifier(&signal, &result, false);
    
    // Format and return result
    return formatResult(env, result);
}
```

### Wear Compose UI

Results are displayed using Jetpack Compose for Wear:

```kotlin
@Composable
fun InferenceChip(inferenceText: String) {
    Chip(
        onClick = { /* Optional action */ },
        label = {
            Text(
                text = inferenceText,
                style = MaterialTheme.typography.body1
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}
```

## Customization

### Add Gyroscope Data

```kotlin
private var gyroscope: Sensor? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
}

override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> {
            ringBuffer[ringBufferIndex++] = event.values[0]
            ringBuffer[ringBufferIndex++] = event.values[1]
            ringBuffer[ringBufferIndex++] = event.values[2]
        }
        Sensor.TYPE_GYROSCOPE -> {
            ringBuffer[ringBufferIndex++] = event.values[0]
            ringBuffer[ringBufferIndex++] = event.values[1]
            ringBuffer[ringBufferIndex++] = event.values[2]
        }
    }
    
    if (ringBufferIndex >= EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
        ringBufferIndex = 0
        runInference(ringBuffer.clone())
    }
}
```

### Add Heart Rate Monitoring

```kotlin
private var heartRateSensor: Sensor? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
}

override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
        Sensor.TYPE_HEART_RATE -> {
            val heartRate = event.values[0]
            updateHeartRateDisplay(heartRate)
        }
    }
}
```

### Adjust Sensor Sampling Rate

```kotlin
// Faster sampling for quick gestures
sensorManager.registerListener(
    this,
    accelerometer,
    SensorManager.SENSOR_DELAY_GAME  // ~50Hz
)

// Or slower for battery optimization
sensorManager.registerListener(
    this,
    accelerometer,
    SensorManager.SENSOR_DELAY_UI  // ~16Hz
)
```

### Add Confidence Threshold

```kotlin
companion object {
    const val CONFIDENCE_THRESHOLD = 0.75f
}

private fun processInferenceResult(result: String) {
    val (label, confidence) = parseResult(result)
    
    if (confidence >= CONFIDENCE_THRESHOLD) {
        _inferenceResult.value = "$label: ${(confidence * 100).toInt()}%"
        triggerHapticFeedback()
    } else {
        _inferenceResult.value = "Uncertain..."
    }
}
```

### Add Haptic Feedback

```kotlin
import android.os.VibrationEffect
import android.os.Vibrator

private fun triggerHapticFeedback(activity: String) {
    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    
    when (activity) {
        "running" -> vibrator.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )
        "jumping" -> vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
        )
    }
}
```

## Performance Tips

### Optimize Sensor Sampling

Match sensor rate to model requirements:

```kotlin
// For model trained at 50Hz
val samplingPeriodUs = 20_000  // 20ms = 50Hz
sensorManager.registerListener(
    this,
    accelerometer,
    samplingPeriodUs
)
```

### Reduce Inference Frequency

Run inference less often to save battery:

```kotlin
private var inferenceCounter = 0
private val inferenceEveryNSamples = 5

override fun onSensorChanged(event: SensorEvent) {
    // Collect data
    ringBuffer[ringBufferIndex++] = event.values[0]
    ringBuffer[ringBufferIndex++] = event.values[1]
    ringBuffer[ringBufferIndex++] = event.values[2]
    
    if (ringBufferIndex >= EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
        ringBufferIndex = 0
        inferenceCounter++
        
        // Only run every 5th time buffer fills
        if (inferenceCounter >= inferenceEveryNSamples) {
            inferenceCounter = 0
            runInference(ringBuffer.clone())
        }
    }
}
```

### Use EON Compiler

When exporting your model, enable **EON Compiler** for significant performance and battery improvements.

### Background Processing

For continuous monitoring, use WorkManager:

```kotlin
class MotionMonitorWorker(context: Context, params: WorkerParameters) 
    : Worker(context, params) {
    
    override fun doWork(): Result {
        // Monitor motion in background
        return Result.success()
    }
}
```

## Troubleshooting

### Sensor Data Not Detected

**Solution**: Verify sensor availability:

```kotlin
if (accelerometer == null) {
    Log.e("Sensor", "Accelerometer not available on this device")
    // Fall back to alternative or show error
}
```

### Incorrect Classifications

**Possible causes**:
- Sensor orientation mismatch
- Wrong sampling rate
- Buffer size doesn't match model

**Solution**: Verify buffer size matches model:

```kotlin
// Check model expects 375 values (125 samples of x,y,z)
private const val EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE = 375
```

### High Battery Drain

**Solutions**:
- Reduce sensor sampling rate
- Reduce inference frequency
- Unregister sensors when app is in background:

```kotlin
override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
}
```

### App Crashes on WearOS Emulator

**Cause**: Emulator may not have all sensors

**Solution**: Test on physical WearOS device or add null checks:

```kotlin
accelerometer?.also { sensor ->
    sensorManager.registerListener(this, sensor, samplingRate)
} ?: run {
    Log.w("Sensor", "Accelerometer not available, using mock data")
}
```

## Project Structure

```
example_motion_WearOS/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/
│   │   │   │   ├── edge-impulse-sdk/      # Your model SDK
│   │   │   │   ├── model-parameters/      # Model metadata
│   │   │   │   ├── tflite-model/          # TFLite model
│   │   │   │   ├── tensorflow-lite/       # TFLite libraries
│   │   │   │   ├── wearosinference.cpp    # JNI inference code
│   │   │   │   └── CMakeLists.txt         # Build configuration
│   │   │   ├── java/com/edgeimpulse/edgeimpulsewearos/
│   │   │   │   └── presentation/
│   │   │   │       ├── MainActivity.kt    # Sensor handling & UI
│   │   │   │       └── theme/             # Wear UI theme
│   │   │   ├── res/
│   │   │   │   └── values/
│   │   │   │       └── strings.xml
│   │   │   └── AndroidManifest.xml        # Sensor permissions
│   │   └── build.gradle.kts               # App dependencies
│   └── build.gradle.kts
└── README.md
```

## Advanced Features

### Activity Logging

Track activity over time:

```kotlin
data class ActivityLog(
    val timestamp: Long,
    val activity: String,
    val confidence: Float,
    val duration: Long
)

private val activityHistory = mutableListOf<ActivityLog>()

private fun logActivity(activity: String, confidence: Float) {
    activityHistory.add(ActivityLog(
        timestamp = System.currentTimeMillis(),
        activity = activity,
        confidence = confidence,
        duration = 0L
    ))
}
```

### Fall Detection Alert

```kotlin
private fun onFallDetected() {
    // Vibrate alert
    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createWaveform(
        longArrayOf(0, 500, 100, 500, 100, 500), -1
    ))
    
    // Send notification
    showFallAlert()
    
    // Optional: Send emergency contact alert
    sendEmergencyAlert()
}
```

### Workout Tracking

```kotlin
data class WorkoutSession(
    val startTime: Long,
    var endTime: Long = 0,
    val activities: MutableList<String> = mutableListOf(),
    var totalCalories: Int = 0
)

private var currentWorkout: WorkoutSession? = null

private fun startWorkout() {
    currentWorkout = WorkoutSession(System.currentTimeMillis())
}

private fun trackActivity(activity: String) {
    currentWorkout?.activities?.add(activity)
    updateCalories(activity)
}
```

### Complications Support

Add activity data to watch face:

```kotlin
class ActivityComplicationService : ComplicationProviderService() {
    override fun onComplicationUpdate(
        complicationId: Int,
        type: Int,
        callback: ComplicationUpdateCallback
    ) {
        val currentActivity = getCurrentActivity()
        
        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(currentActivity).build(),
            ComplicationText.EMPTY
        ).build()
        
        callback.onUpdateComplication(data)
    }
}
```

## Supported Platforms

- **WearOS 3.0+**: Recommended
- **WearOS 2.0**: Supported (API 28+)
- **64-bit ARM**: arm64-v8a (most WearOS devices)
- **Minimum Android**: API 30 (Android 11)
- **Target Android**: API 35 (Android 15)

## Use Cases

- **Fitness Tracking**: Recognize exercises (push-ups, squats, jumping jacks)
- **Gesture Control**: Control smart home devices with hand gestures
- **Fall Detection**: Alert caregivers when falls are detected
- **Activity Recognition**: Track daily activities (walking, running, cycling)
- **Form Analysis**: Monitor exercise form and provide feedback
- **Sleep Tracking**: Detect sleep positions and movements

## Resources

- 📚 [Motion Classification Tutorial](https://docs.edgeimpulse.com/docs/tutorials/continuous-motion-recognition)
- 📚 [WearOS Development Guide](https://developer.android.com/training/wearables)
- 📚 [Sensor Best Practices](https://developer.android.com/guide/topics/sensors/sensors_overview)

**Need help?** Join the [Edge Impulse Forum](https://forum.edgeimpulse.com/) or check the [documentation](https://docs.edgeimpulse.com/).
