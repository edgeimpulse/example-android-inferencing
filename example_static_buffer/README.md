# Edge Impulse Static Buffer Inference on Android

Simple proof-of-concept for running Edge Impulse models on Android with static test data. Perfect for validating model integration before adding sensor inputs or camera feeds.

## Features

- **Minimal implementation** for testing model integration
- **Static input data** for reproducible results
- **On-device inference** with TensorFlow Lite
- **XNNPACK acceleration** for optimal performance
- **Multiple model type support**:
  - Classification
  - Object detection
  - Anomaly detection (visual or data)
- **No sensor permissions required** - runs entirely offline

## What You'll Build

A minimal Android app that runs inference on pre-defined test data and displays the results. This is ideal for:
- Testing model deployment workflow
- Validating model accuracy on known inputs
- Debugging inference code
- Learning the Edge Impulse Android integration

## Prerequisites

- [Edge Impulse account](https://edgeimpulse.com/signup)
- Trained Edge Impulse model (any type)
- Android Studio (Ladybug 2024.2.2 or later)
- Android device or emulator (API 24+)
- Android NDK 27.0.12077973
- CMake 3.22.1

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/edgeimpulse/example-android-inferencing.git
cd example-android-inferencing/example_static_buffer
```

### 2. Export Your Model

1. In Edge Impulse Studio, go to **Deployment**
2. Select **Android (C++ library)**
3. Enable **EON Compiler** (optional, recommended)
4. Click **Build** and download the `.zip`

### 3. Integrate the Model

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

### 4. Get Test Data

1. In Edge Impulse Studio, go to **Model testing**
2. Select a test sample
3. Click **Show raw features** (or **Three dots menu** → **Show classification**)
4. Copy the raw features array

Example:
```
-1.2, 0.3, 1.5, -0.8, 2.1, ...
```

### 5. Add Test Data to Code

Open `app/src/main/cpp/native-lib.cpp` and replace the `raw_features` vector:

```cpp
std::vector<float> raw_features = {
    // Paste your raw features here
    -1.2, 0.3, 1.5, -0.8, 2.1, 0.7, -1.3, 0.9, 1.1, -0.5,
    // ... (all your feature values)
};
```

### 6. Build and Run

1. Open the project in Android Studio
2. **Build** → **Make Project**
3. Connect your Android device or start an emulator
4. Run the app
5. View inference results on screen

## How It Works

### Static Data Input

The app uses pre-defined feature data stored in a vector:

```cpp
std::vector<float> raw_features = {
    // Your test data here
};
```

This data represents pre-processed sensor readings, audio samples, or image pixels that match your model's input requirements.

### Signal Creation

The features are wrapped in an Edge Impulse signal structure:

```cpp
signal_t signal;
signal.total_length = raw_features.size();
signal.get_data = [](size_t offset, size_t length, float *out_ptr) {
    for (size_t i = 0; i < length; i++) {
        out_ptr[i] = raw_features[offset + i];
    }
    return EIDSP_OK;
};
```

### Native Inference

Inference runs in C++ and returns results to Kotlin:

```cpp
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_test_1cpp_MainActivity_runInference(
    JNIEnv* env, jobject) {
    
    // Create signal from static data
    signal_t signal;
    signal.total_length = raw_features.size();
    signal.get_data = &get_signal_data;
    
    // Run classifier
    ei_impulse_result_t result = {0};
    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);
    
    // Convert result to Java object
    return createResultObject(env, result);
}
```

### Display Results

The Kotlin activity receives and displays the inference results:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    val result = runInference()
    
    if (result != null) {
        displayClassification(result.classification)
        displayObjectDetections(result.objectDetections)
        displayAnomalyScore(result.anomalyResult)
    }
}
```

## Customization

### Test Multiple Samples

Create an array of test samples and run inference on each:

```cpp
std::vector<std::vector<float>> test_samples = {
    {-1.2, 0.3, 1.5, /* sample 1 */},
    {0.8, -0.5, 2.1, /* sample 2 */},
    {1.3, 0.9, -0.7, /* sample 3 */}
};

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_test_1cpp_MainActivity_runInferenceOnSample(
    JNIEnv* env, jobject, jint sampleIndex) {
    
    raw_features = test_samples[sampleIndex];
    // ... run inference
}
```

### Add Benchmark Testing

Measure inference performance:

```kotlin
private fun benchmarkInference(iterations: Int = 100) {
    val times = mutableListOf<Long>()
    
    repeat(iterations) {
        val start = System.nanoTime()
        runInference()
        val end = System.nanoTime()
        times.add((end - start) / 1_000_000) // Convert to ms
    }
    
    val avgTime = times.average()
    val minTime = times.minOrNull()
    val maxTime = times.maxOrNull()
    
    Log.d("Benchmark", "Avg: ${avgTime}ms, Min: ${minTime}ms, Max: ${maxTime}ms")
}
```

### Compare Model Versions

Test accuracy across different model versions:

```kotlin
data class ModelTest(
    val modelName: String,
    val expectedLabel: String,
    val actualResult: InferenceResult
)

private fun runModelComparison() {
    val models = listOf("model_v1", "model_v2", "model_v3")
    val results = models.map { modelName ->
        loadModel(modelName)
        ModelTest(modelName, "expected", runInference())
    }
    
    displayComparisonResults(results)
}
```

### Generate Random Test Data

For stress testing:

```cpp
std::vector<float> generateRandomFeatures(size_t size) {
    std::vector<float> features(size);
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_real_distribution<> dis(-2.0, 2.0);
    
    for (size_t i = 0; i < size; i++) {
        features[i] = dis(gen);
    }
    
    return features;
}
```

## Testing Different Model Types

### Classification Model

```cpp
std::vector<float> raw_features = {
    // Sensor data: 1 second of 3-axis accelerometer at 100Hz = 300 values
    -1.2, 0.3, 1.5, -0.8, 2.1, 0.7, /* ... 294 more values */
};
```

Expected output:
```
Classification:
walking: 0.89
standing: 0.07
sitting: 0.04
```

### Object Detection Model

```cpp
std::vector<float> raw_features = {
    // Image data: 96x96 RGB image = 27,648 values
    0.12, 0.45, 0.67, /* ... pixel values */
};
```

Expected output:
```
Object detection:
person: 0.92, 120, 80, 45, 60
car: 0.85, 200, 150, 80, 50
```

### Anomaly Detection

```cpp
std::vector<float> raw_features = {
    // Time-series sensor data
    1.2, 1.3, 1.25, 1.28, 1.27, /* normal pattern */
    5.6, 6.1, 5.9, /* anomaly spike */
    1.3, 1.25, 1.26 /* back to normal */
};
```

Expected output:
```
Anomaly score: 0.78
```

## Performance Tips

### Enable XNNPACK

Already included in `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}
```

### Optimize Build Settings

```cmake
# In CMakeLists.txt
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
```

### Use EON Compiler

When exporting your model from Edge Impulse, enable **EON Compiler** for significant performance improvements.

## Troubleshooting

### Wrong Number of Features Error

**Error**: `ERR: Wrong number of features, expected X but got Y`

**Solution**: Verify your raw_features array size matches model requirements:

```cpp
// Check expected size
#include "model-parameters/model_metadata.h"
// EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE should match raw_features.size()
```

### Inference Returns Null

**Possible causes**:
- Model files not copied correctly
- CMakeLists.txt replaced (use original)
- Native library not loaded

**Solution**: Check logcat for detailed error messages:

```bash
adb logcat | grep EdgeImpulse
```

### Classification Scores Don't Match Studio

**Possible causes**:
- Feature scaling mismatch
- Different preprocessing applied
- Wrong input data format

**Solution**: Copy raw features exactly from "Show raw features" in Studio, including decimal precision.

### Build Fails with NDK Errors

**Solution**: Verify NDK and CMake versions match requirements:

```bash
# In Android Studio: Tools → SDK Manager → SDK Tools
# ✓ NDK (Side by side) 27.0.12077973
# ✓ CMake 3.22.1
```

## Project Structure

```
example_static_buffer/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/
│   │   │   │   ├── edge-impulse-sdk/     # Your model SDK
│   │   │   │   ├── model-parameters/     # Model metadata
│   │   │   │   ├── tflite-model/         # TFLite model
│   │   │   │   ├── native-lib.cpp        # Static data & inference
│   │   │   │   └── CMakeLists.txt        # Build configuration
│   │   │   ├── java/com/example/test_cpp/
│   │   │   │   └── MainActivity.kt       # UI and result display
│   │   │   ├── res/
│   │   │   │   └── layout/
│   │   │   │       └── activity_main.xml # Simple text view
│   │   │   └── AndroidManifest.xml       # No special permissions
│   │   └── build.gradle.kts              # App dependencies
│   └── build.gradle.kts
└── README.md
```

## Next Steps

Once you've validated your model works with static data, you can:

1. **Add real sensors** - Check out `example_kws` for audio or `example_motion_WearOS` for accelerometer
2. **Add camera input** - See `example_camera_inference` for computer vision
3. **Implement continuous inference** - Add ring buffers for streaming data
4. **Deploy to production** - Optimize performance and add error handling

## Advanced Features

### Batch Inference

Run inference on multiple samples efficiently:

```cpp
std::vector<ei_impulse_result_t> runBatchInference(
    std::vector<std::vector<float>>& samples) {
    
    std::vector<ei_impulse_result_t> results;
    
    for (auto& sample : samples) {
        raw_features = sample;
        ei_impulse_result_t result = {0};
        run_classifier(&signal, &result, false);
        results.push_back(result);
    }
    
    return results;
}
```

### Model Accuracy Testing

Validate model against labeled test set:

```kotlin
data class TestSample(
    val features: FloatArray,
    val expectedLabel: String
)

private fun calculateAccuracy(testSet: List<TestSample>): Float {
    var correct = 0
    
    testSet.forEach { sample ->
        setFeatures(sample.features)
        val result = runInference()
        val predictedLabel = result.classification?.maxByOrNull { it.value }?.key
        
        if (predictedLabel == sample.expectedLabel) {
            correct++
        }
    }
    
    return (correct.toFloat() / testSet.size) * 100
}
```

### Export Test Results

```kotlin
private fun exportResults(results: List<InferenceResult>) {
    val csv = results.joinToString("\n") { result ->
        "${result.timing.classification_us},${result.classification}"
    }
    
    File(getExternalFilesDir(null), "test_results.csv").writeText(csv)
}
```

## Supported Platforms

- **64-bit ARM**: arm64-v8a (recommended)
- **32-bit ARM**: armeabi-v7a (requires configuration)
- **x86/x86_64**: Emulator support (add to abiFilters)
- **Minimum Android**: API 24 (Android 7.0)
- **Target Android**: API 35 (Android 15)

## Resources

- 📚 [Android Deployment Guide](https://docs.edgeimpulse.com/docs/deployment/running-your-impulse-android)
- 📚 [Model Testing Documentation](https://docs.edgeimpulse.com/docs/edge-impulse-studio/model-testing)

**Need help?** Join the [Edge Impulse Forum](https://forum.edgeimpulse.com/) or check the [documentation](https://docs.edgeimpulse.com/).
