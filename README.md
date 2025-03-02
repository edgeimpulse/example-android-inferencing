
# example-android-inferencing

## Edge Impulse Inferencing on Android
This repository contains a minimal example for running an **Edge Impulse** machine learning model on an **Android** device using **Android NDK** and **TensorFlow Lite**.

## Prerequisites

### Edge Impulse:
- Ensure you have followed the **Android guide** and have a trained model.
- Export your model as a **C++ library** from **Edge Impulse Studio**.

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
cd example-inferencing-android
```

## Run the Windows / Linux / OSX script to fetch resources

```sh
cd example-android-inferencing/example_static_buffer/app/src/main/cpp/tflite
sh download_tflite_libs.bat # download_tflite_libs.sh for OSX and Linux
```

## Import the Project to Android Studio

Choose the project to import

- [WearOS](example_motion_WearOS)
- [Android](https://github.com/edgeimpulse/example-android-inferencing/example_camera_inference)
- [Static Buffer](https://github.com/edgeimpulse/example-android-inferencing/example_static_buffer)

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
1. Obtain the test feature set from **Edge Impulse Studio**.
2. Paste the test feature set into the appropriate location in the project.

## Build and Run the Project
1. In **Android Studio**, click on **Build** > **Make Project**.
2. Once the build is successful, run the project on an Android device or emulator.

