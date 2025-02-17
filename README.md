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
cd example-android-inferencing/Test_cpp/app/src/main/cpp/tflite
sh download_tflite_libs.bat # download_tflite_libs.sh for OSX and Linux
```

### import the project to Android Studio

### Download CPP project from Edge Impulse

### Paste in the test feature set for the cpp test


