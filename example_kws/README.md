# Edge Impulse Keyword Spotting on Android

Real-time audio keyword recognition on Android using Edge Impulse and TensorFlow Lite. Deploy wake word detection, voice commands, and audio event recognition entirely on-device.

https://github.com/user-attachments/assets/c9f02018-c9a0-42b2-869d-66c167db71d5

## Features

-  **Continuous keyword recognition** with ring buffer
-  **On-device inference** with TensorFlow Lite
-  **XNNPACK acceleration** for faster inference
-  **Low latency** detection (< 100ms)
-  **Customizable confidence threshold**
-  **Visual feedback** on detection

## What You'll Build

An Android app that continuously listens for spoken keywords and provides real-time classification with confidence scores.

## Prerequisites

- [Edge Impulse account](https://edgeimpulse.com/signup)
- Trained **audio keyword spotting** model
- Android Studio (Ladybug 2024.2.2 or later)
- Android device with microphone (API 24+)
- Android NDK 27.0.12077973
- CMake 3.22.1

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/edgeimpulse/example-android-inferencing.git
cd example-android-inferencing/example_kws
```

### 2. Download TensorFlow Lite Libraries

```bash
cd app/src/main/cpp/tflite

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
├── native-lib.cpp
└── CMakeLists.txt (don't replace this)
```

### 5. Build and Run

1. Open the project in Android Studio
2. **Build** → **Make Project**
3. Connect your Android device
4. Run the app
5. Grant microphone permission when prompted
6. Speak your keywords!

## How It Works

### Audio Capture

The app uses `AudioRecord` to capture real-time audio at 16kHz mono:

```kotlin
audioRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    16000,  // Sample rate
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)
```

### Ring Buffer

Audio samples are stored in a ring buffer that matches your model's input window (e.g., 1 second):

```kotlin
private val audioRingBuffer = RingBuffer(16000) // 1 second at 16kHz

private fun processAudioBuffer(buffer: ShortArray, size: Int) {
    audioRingBuffer.write(buffer, size)
    
    if (audioRingBuffer.isFull()) {
        val features = audioRingBuffer.read()
        runInference(features)
    }
}
```

### Native Inference

Audio processing and inference happen in C++ via JNI:

```cpp
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_kws_EIClassifierAudio_run(
    JNIEnv* env, jobject, jshortArray audioData) {
    
    // Convert to float features
    std::vector<float> features = convertAudio(audioData);
    
    // Run classifier
    ei_impulse_result_t result = {0};
    run_classifier(&signal, &result, false);
    
    return createResultObject(env, result);
}
```

## Customization

### Adjust Confidence Threshold

```kotlin
companion object {
    const val CONFIDENCE_THRESHOLD = 0.7f  // 70% confidence
}

private fun isValidKeyword(result: ClassificationResult): Boolean {
    return result.classification.any { it.score >= CONFIDENCE_THRESHOLD }
}
```

### Change Inference Frequency

```kotlin
// Run inference every 500ms instead of every buffer fill
private val inferenceInterval = 500L

private fun processAudioBuffer(buffer: ShortArray, size: Int) {
    audioRingBuffer.write(buffer, size)
    
    if (System.currentTimeMillis() - lastInferenceTime > inferenceInterval 
        && audioRingBuffer.isFull()) {
        runInference(audioRingBuffer.read())
        lastInferenceTime = System.currentTimeMillis()
    }
}
```

### Add Wake Word Triggering

```kotlin
private val wakeWords = setOf("hey_device", "wake_up")

private fun onKeywordDetected(keyword: String) {
    if (keyword in wakeWords) {
        // Wake word detected
        startCommandMode()
        playWakeSound()
    } else if (isInCommandMode) {
        // Process voice command
        executeCommand(keyword)
    }
}
```

### Single-Shot Detection Mode

Detect once, then pause for 2 seconds:

```kotlin
private var isProcessing = false

private fun processAudioBuffer(buffer: ShortArray, size: Int) {
    if (!isProcessing) {
        audioRingBuffer.write(buffer, size)
        
        if (audioRingBuffer.isFull()) {
            isProcessing = true
            runInference(audioRingBuffer.read())
            
            Handler(Looper.getMainLooper()).postDelayed({
                isProcessing = false
            }, 2000)
        }
    }
}
```

## Performance Tips

### Reduce Buffer Size for Lower Latency

```kotlin
val bufferSize = AudioRecord.getMinBufferSize(
    SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT
) / 2  // Half the minimum buffer size
```

### Enable XNNPACK (Already Included)

XNNPACK delegate is already enabled in `CMakeLists.txt`:

```cmake
target_link_libraries(kws_cpp
    tensorflow-lite
    XNNPACK
    xnnpack-delegate
)
```

### Battery Optimization

```kotlin
// Reduce inference when idle
private fun adjustInferenceRate(activity: String) {
    inferenceInterval = if (activity == "idle") 2000L else 500L
}
```

## Troubleshooting

### Audio Permission Denied

**Solution**: Request permission at runtime:

```kotlin
if (ContextCompat.checkSelfPermission(this, 
    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
    
    ActivityCompat.requestPermissions(this,
        arrayOf(Manifest.permission.RECORD_AUDIO),
        REQUEST_CODE_AUDIO)
}
```

### Keywords Not Detected Reliably

**Possible causes**:
- Low confidence threshold → Increase to 0.8
- Background noise → Train model with noise samples
- Audio format mismatch → Verify 16kHz mono format
- Model window size mismatch → Check ring buffer size matches model

### High CPU Usage / Battery Drain

**Solutions**:
- Reduce inference frequency (500ms → 1000ms)
- Lower sample rate if model supports it (16kHz → 8kHz)
- Add voice activity detection (VAD)
- Use sleep mode when idle:

```kotlin
if (System.currentTimeMillis() - lastDetectionTime > 5000) {
    // No keyword in 5 seconds, reduce inference
    inferenceInterval = 2000L
}
```

### Audio Cutting In/Out

**Cause**: Buffer underrun

**Solution**: Increase buffer size:

```kotlin
val bufferSize = AudioRecord.getMinBufferSize(...) * 2
```

## Project Structure

```
example_kws/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/
│   │   │   │   ├── edge-impulse-sdk/     # Your model SDK
│   │   │   │   ├── model-parameters/     # Model metadata
│   │   │   │   ├── tflite-model/         # TFLite model
│   │   │   │   ├── tflite/               # TFLite libraries
│   │   │   │   ├── native-lib.cpp        # JNI inference code
│   │   │   │   └── CMakeLists.txt        # Build configuration
│   │   │   ├── java/com/example/kws/
│   │   │   │   ├── MainActivity.kt       # UI and audio capture
│   │   │   │   ├── EIClassifierAudio.kt  # Native interface
│   │   │   │   └── RingBuffer.kt         # Audio buffer
│   │   │   └── AndroidManifest.xml       # Permissions
│   │   └── build.gradle.kts              # App dependencies
│   └── build.gradle.kts
└── README.md
```

## Advanced Features

### Voice Activity Detection (VAD)

Only run inference when speech is detected:

```kotlin
private fun hasVoiceActivity(buffer: ShortArray): Boolean {
    val energy = buffer.map { (it * it).toLong() }.average()
    return energy > VOICE_THRESHOLD
}
```

### Multiple Language Support

```kotlin
private val languageModels = mapOf(
    "en" to "model_english.eim",
    "es" to "model_spanish.eim"
)

private fun switchLanguage(language: String) {
    loadModel(languageModels[language]!!)
}
```

### Detection Logging

```kotlin
data class Detection(
    val keyword: String,
    val confidence: Float,
    val timestamp: Long
)

private val detectionLog = mutableListOf<Detection>()

private fun onKeywordDetected(result: ClassificationResult) {
    detectionLog.add(Detection(
        keyword = result.label,
        confidence = result.score,
        timestamp = System.currentTimeMillis()
    ))
}
```

## Supported Platforms

- **64-bit ARM**: arm64-v8a (recommended)
- **32-bit ARM**: armeabi-v7a (requires configuration changes)
- **Minimum Android**: API 24 (Android 7.0)
- **Target Android**: API 35 (Android 15)

## Resources

- 📚 [Full Tutorial](https://docs.edgeimpulse.com/tutorials/topics/android/keyword-spotting)

**Need help?** Join the [Edge Impulse Forum](https://forum.edgeimpulse.com/) or check the [documentation](https://docs.edgeimpulse.com/).
