
# example-android-inferencing

## Edge Impulse Inferencing on Android
This repository contains a minimal example for running an **Edge Impulse** machine learning model on an **Android** device using **Android NDK** and **TensorFlow Lite**.

See the [Android Documentation](https://docs.edgeimpulse.com/docs/run-inference/cpp-library/running-your-impulse-android).

## Prerequisites

- https://edgeimpulse.com/signup
  
### Edge Impulse:
- Ensure you have followed the **[on Android guide](https://docs.edgeimpulse.com/docs/run-inference/cpp-library/running-your-impulse-android)** and have a trained model.
- Export your model as a **C++ library** from **Edge Impulse Studio**.

### Workshop
- 1. Join: https://edgeimpulse.com/signup
- 2. Follow one of the tutorial guides for beginners [here](https://docs.edgeimpulse.com/docs/readme/for-beginners#tutorials-and-resources-for-beginners)
- 3. Export the C++ Binary [Visual Anomaly](https://docs.edgeimpulse.com/docs/edge-impulse-studio/learning-blocks/visual-anomaly-detection) or download the prebuilt GMM Cracks Demo on the workshop download the C++ export [here](https://drive.google.com/file/d/1oXP83vHUDs7iS6uuAlZilmrWyDYsBc9t/view?usp=sharing)
- 4. Follow the rest of this repo
- 5. Now knowledgable Android Developers can make a change to the [Kotlin](https://developer.android.com/get-started/codelabs) appliction logic to build your own app around the runInference function, e.g. count instances of detections, add thresholding to only detect beyond 70% of confidence, change the UI.

### Android Development:
- Install **Android Studio**.
- Install **Android NDK** and **CMake** via the **Android Studio SDK Manager**.
The example is tested to work with Android Studio Ladybug Feature Drop | 2024.2.2, Android API 35, Android SDK Build-Tools 35.0.1, NDK 27.0.12077973, CMake 3.22.1.
---

## Cloning the Base Repository
We created an example repository that contains an **Android Studio project with C++ support**.
Clone or download this repository:

```sh
git clone https://github.com/edgeimpulse/example-android-inferencing.git
cd example-android-inferencing
```

## Run the Windows / Linux / OSX script to fetch resources

```sh
cd example-android-inferencing/example_static_buffer/app/src/main/cpp/tflite
sh download_tflite_libs.bat # download_tflite_libs.sh for OSX and Linux
```

## Import the Project to Android Studio

Choose the project to import

- [WearOS](example_motion_WearOS)
- [Android](example_camera_inference)
- [Static Buffer](example_static_buffer)

1. Open **Android Studio**.
2. Select **Open an existing Android Studio project**.
3. Navigate to the cloned repository and select it.

## Download CPP Project from Edge Impulse
1. Go to **Edge Impulse Studio**.
2. Export your trained model as a **C++ library**.
3. Download the exported model.

## Integrate the Model with the Project
1. Extract the downloaded **C++ library**.
2. Copy the extracted files into the `example-android-inferencing/example_static_buffer/app/src/main/cpp` directory, dont copy the CMake.txt file.

## Paste in the Test Feature Set for the CPP Test
1. Obtain the test feature set from **Edge Impulse Studio** test impulse tab.
2. Paste the test feature set into the raw_features array the native_lib.cpp licated in the cpp directory.

```cpp
std::vector<float> raw_features = {
    // Copy raw features here (e.g. from the 'Model testing' page)
};
```

## Build and Run the Project
1. In **Android Studio**, click on **Build** > **Make Project**.
2. Once the build is successful, run the project on an Android device or emulator.


## Adding New Sensors to the WearOS example
If you want to integrate additional sensors, such as a Gyroscope or Heart Rate Sensor, follow these steps:

1. Enable the Sensor in the Code
In MainActivity.kt, locate the sensor initialization section and uncomment the corresponding lines:

```kotlin
// Uncomment to add Gyroscope support
private var gyroscope: Sensor? = null

// Uncomment to add Heart Rate sensor support
private var heartRateSensor: Sensor? = null
```

2. Initialize the Sensor in onCreate
Inside onCreate(), uncomment and initialize the sensor:

```kotlin
 gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
// heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
```
3. Register the Sensor in onResume
To start collecting sensor data when the app is active, uncomment the registration logic:

```kotlin
 gyroscope?.also {
     sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
 }

// heartRateSensor?.also {
//     sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
// }
```
4. Handle Sensor Data in onSensorChanged
Modify the onSensorChanged() function to collect new sensor data:

```kotlin
 Gyroscope data
 Sensor.TYPE_GYROSCOPE -> {
     ringBuffer[ringBufferIndex++] = event.values[0] // X rotation
     ringBuffer[ringBufferIndex++] = event.values[1] // Y rotation
     ringBuffer[ringBufferIndex++] = event.values[2] // Z rotation
 }

// Heart Rate data
// Sensor.TYPE_HEART_RATE -> {
//     ringBuffer[ringBufferIndex++] = event.values[0] // Heart rate BPM
// }
```
5. Unregister the Sensor in onPause
To save battery and improve performance, ensure sensors stop when the app is paused:

```kotlin
sensorManager.unregisterListener(this)
```
6. Run the App and Verify
Build and deploy the app on a WearOS device.
Check logs for new sensor data.
Ensure inference runs correctly with the additional inputs.



### Troubleshooting, and other deployment hardware

Testing on devices without ready access to a camera, like Device Cloud or VR headsets that dont allow you to use the passthrough camera:

```
override fun onResume() {
    super.onResume()

    //Read the asset
    val bmp = assets.open("test.jpg").use { BitmapFactory.decodeStream(it) }

    //Resize to the model’s input — helper does this for you
    val resized = EIImageHelper.resizeBitmap(bmp)

    //Run inference (synchronous, one-shot)
    val result = EIClassifierImage.run(resized)

    //Show scores in the existing TextView
    runOnUiThread { resultText.text = result.format() }
}
```

