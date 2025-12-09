# Edge Impulse Camera Inference on Android

Real-time image classification and object detection on Android using Edge Impulse and TensorFlow Lite. Deploy computer vision models for image classification, object detection, and visual anomaly detection entirely on-device.

## Features

- **Real-time camera inference** with live preview
- **CameraX integration** for efficient camera handling
- **On-device inference** with TensorFlow Lite
- **XNNPACK acceleration** for faster inference
- **Multiple model types**:
  - Image classification
  - Object detection with bounding boxes
  - Visual anomaly detection
- **Visual overlays** for detection results
- **Low latency** processing

## What You'll Build

An Android app that captures camera frames in real-time and performs computer vision inference, displaying classification results, detected objects with bounding boxes, or anomaly regions.

## Prerequisites

- [Edge Impulse account](https://edgeimpulse.com/signup)
- Trained **image classification** or **object detection** model
- Android Studio (Ladybug 2024.2.2 or later)
- Android device with camera (API 24+)
- Android NDK 27.0.12077973
- CMake 3.22.1

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/edgeimpulse/example-android-inferencing.git
cd example-android-inferencing/example_camera_inference
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
├── native-lib.cpp
└── CMakeLists.txt (don't replace this)
```

### 5. Build and Run

1. Open the project in Android Studio
2. **Build** → **Make Project**
3. Connect your Android device
4. Run the app
5. Grant camera permission when prompted
6. Point camera at objects to classify!

## How It Works

### Camera Setup

The app uses CameraX for efficient camera capture:

```kotlin
private fun startCamera() {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        
        val preview = Preview.Builder().build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            
        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImage(imageProxy)
        }
        
        cameraProvider.bindToLifecycle(
            this, cameraSelector, preview, imageAnalysis
        )
    }, ContextCompat.getMainExecutor(this))
}
```

### Image Processing

Camera frames are converted to RGB format and passed to the classifier:

```kotlin
private fun processImage(imageProxy: ImageProxy) {
    val bitmap = imageProxy.toBitmap()
    val rgbBytes = bitmapToRGB(bitmap)
    
    lifecycleScope.launch(Dispatchers.Default) {
        val result = runInference(rgbBytes)
        displayResults(result)
    }
    
    imageProxy.close()
}
```

### Native Inference

Image processing and inference happen in C++ via JNI:

```cpp
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_test_camera_MainActivity_runInference(
    JNIEnv* env, jobject, jbyteArray imageData) {
    
    // Convert image to model input format
    signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * 
                          EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data = &ei_camera_get_data;
    
    // Run classifier
    ei_impulse_result_t result = {0};
    run_classifier(&signal, &result, false);
    
    return createResultObject(env, result);
}
```

### Object Detection Overlay

Detected objects are displayed with bounding boxes:

```kotlin
class BoundingBoxOverlay : View {
    var boundingBoxes: List<BoundingBox> = emptyList()
    
    override fun onDraw(canvas: Canvas) {
        boundingBoxes.forEach { box ->
            val rect = Rect(box.x, box.y, 
                           box.x + box.width, 
                           box.y + box.height)
            canvas.drawRect(rect, paint)
            canvas.drawText("${box.label} ${box.confidence}", 
                          box.x, box.y - 10, textPaint)
        }
    }
}
```

## Customization

### Adjust Confidence Threshold

```kotlin
companion object {
    const val CONFIDENCE_THRESHOLD = 0.6f  // 60% confidence
}

private fun filterDetections(detections: List<BoundingBox>): List<BoundingBox> {
    return detections.filter { it.confidence >= CONFIDENCE_THRESHOLD }
}
```

### Change Camera Resolution

```kotlin
private val imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(640, 480))  // VGA resolution
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
```

### Adjust Inference Frequency

```kotlin
private var lastInferenceTime = 0L
private val inferenceInterval = 200L  // Run every 200ms

private fun processImage(imageProxy: ImageProxy) {
    val currentTime = System.currentTimeMillis()
    
    if (currentTime - lastInferenceTime >= inferenceInterval) {
        runInference(imageProxy)
        lastInferenceTime = currentTime
    }
    
    imageProxy.close()
}
```

### Add Classification Filtering

```kotlin
private fun getTopClassification(
    classifications: Map<String, Float>, 
    topN: Int = 3
): List<Pair<String, Float>> {
    return classifications.entries
        .sortedByDescending { it.value }
        .take(topN)
        .map { it.key to it.value }
}
```

### Enable Anomaly Visualization

```kotlin
private fun displayAnomalyGrid(gridCells: List<BoundingBox>) {
    gridCells.forEach { cell ->
        if (cell.confidence > ANOMALY_THRESHOLD) {
            // Highlight anomalous regions in red
            boundingBoxOverlay.addAnomalyCell(cell)
        }
    }
}
```

## Performance Tips

### Use GPU Acceleration

Enable GPU delegate in `CMakeLists.txt`:

```cmake
target_link_libraries(test_camera
    tensorflow-lite
    tensorflow-lite-gpu-delegate
)
```

### Reduce Image Resolution

Lower resolution = faster inference:

```cpp
#define CAMERA_INPUT_WIDTH 320   // Reduce from 640
#define CAMERA_INPUT_HEIGHT 240  // Reduce from 480
```

### Optimize Camera Settings

```kotlin
private val imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(480, 640))
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setImageQueueDepth(2)  // Reduce queue depth
    .build()
```

### Use EON Compiler

When exporting your model, enable **EON Compiler** for optimized inference performance.

## Troubleshooting

### Camera Permission Denied

**Solution**: Request permission at runtime:

```kotlin
if (ContextCompat.checkSelfPermission(this, 
    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
    
    ActivityCompat.requestPermissions(this,
        arrayOf(Manifest.permission.CAMERA),
        CAMERA_PERMISSION_REQUEST_CODE)
}
```

### Low FPS / Laggy Preview

**Possible solutions**:
- Reduce inference frequency (100ms → 300ms)
- Lower camera resolution
- Enable XNNPACK or GPU acceleration
- Reduce model complexity

### Objects Not Detected Reliably

**Possible causes**:
- Low confidence threshold → Increase to 0.7
- Poor lighting conditions → Add data augmentation during training
- Model trained on different resolution → Match camera resolution to model input
- Camera orientation mismatch → Handle rotation correctly

### Bounding Boxes Misaligned

**Cause**: Resolution or orientation mismatch

**Solution**: Ensure camera frame dimensions match model input:

```kotlin
private fun scaleBoundingBox(box: BoundingBox, 
                             modelWidth: Int, 
                             modelHeight: Int,
                             viewWidth: Int, 
                             viewHeight: Int): BoundingBox {
    val scaleX = viewWidth.toFloat() / modelWidth
    val scaleY = viewHeight.toFloat() / modelHeight
    
    return box.copy(
        x = (box.x * scaleX).toInt(),
        y = (box.y * scaleY).toInt(),
        width = (box.width * scaleX).toInt(),
        height = (box.height * scaleY).toInt()
    )
}
```

### App Crashes on Startup

**Possible causes**:
- TensorFlow Lite libraries not downloaded
- Model files missing from `cpp/` directory
- NDK/CMake version mismatch

**Solution**: Verify all files are in place and rebuild.

## Project Structure

```
example_camera_inference/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/
│   │   │   │   ├── edge-impulse-sdk/      # Your model SDK
│   │   │   │   ├── model-parameters/      # Model metadata
│   │   │   │   ├── tflite-model/          # TFLite model
│   │   │   │   ├── tensorflow-lite/       # TFLite libraries
│   │   │   │   ├── native-lib.cpp         # JNI inference code
│   │   │   │   └── CMakeLists.txt         # Build configuration
│   │   │   ├── java/com/example/test_camera/
│   │   │   │   ├── MainActivity.kt        # Camera & UI
│   │   │   │   └── BoundingBoxOverlay.kt  # Visual overlay
│   │   │   ├── res/
│   │   │   │   └── layout/
│   │   │   │       └── activity_main.xml  # UI layout
│   │   │   └── AndroidManifest.xml        # Permissions
│   │   └── build.gradle.kts               # App dependencies
│   └── build.gradle.kts
└── README.md
```

## Advanced Features

### Multi-Model Support

Switch between different models at runtime:

```kotlin
private var currentModel = "classifier"

private fun loadModel(modelType: String) {
    when(modelType) {
        "classifier" -> loadClassificationModel()
        "detector" -> loadObjectDetectionModel()
        "anomaly" -> loadAnomalyModel()
    }
}
```

### Save Detection Results

```kotlin
data class DetectionEvent(
    val timestamp: Long,
    val label: String,
    val confidence: Float,
    val imagePath: String
)

private fun saveDetection(result: InferenceResult, bitmap: Bitmap) {
    val timestamp = System.currentTimeMillis()
    val imagePath = saveImage(bitmap, timestamp)
    
    // Log detection event
    detectionLog.add(DetectionEvent(
        timestamp, result.topLabel, result.topConfidence, imagePath
    ))
}
```

### Add Face Detection

Combine with face detection for privacy filtering:

```kotlin
private fun blurFaces(bitmap: Bitmap): Bitmap {
    val faceDetector = FaceDetector.Builder(context).build()
    val faces = faceDetector.detect(Frame.Builder().setBitmap(bitmap).build())
    
    faces.forEach { face ->
        // Blur or pixelate face regions
        blurRegion(bitmap, face.boundingBox)
    }
    
    return bitmap
}
```

## Supported Platforms

- **64-bit ARM**: arm64-v8a (recommended)
- **32-bit ARM**: armeabi-v7a (requires configuration)
- **Minimum Android**: API 24 (Android 7.0)
- **Target Android**: API 35 (Android 15)

## Resources

- 📚 [Image Classification Tutorial](https://docs.edgeimpulse.com/docs/tutorials/image-classification)
- 📚 [Object Detection Tutorial](https://docs.edgeimpulse.com/docs/tutorials/object-detection)

**Need help?** Join the [Edge Impulse Forum](https://forum.edgeimpulse.com/) or check the [documentation](https://docs.edgeimpulse.com/).
