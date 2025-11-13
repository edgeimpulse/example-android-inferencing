# QNN Hardware Acceleration (Android)

Demonstrating Object counting as we move on to more advanced use cases


## Script WIP for QNN Libs 

<img width="1026" height="174" alt="image" src="https://github.com/user-attachments/assets/a13eee47-77ae-4098-bb92-76ce58820ef7" />


```
sh ./fetchqnnlibs.sh
```

### First Download the Qualcomm SDK

<img width="466" height="252" alt="image" src="https://github.com/user-attachments/assets/aff006dc-1129-4e8a-a56a-5fbc05fc0472" />


Locate your QAIRT libs from https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk

Common locations:
```
macOS/Linux: /opt/qairt/<version>/ or ~/qairt/<version>/

Windows: C:\qairt\<version>\
```
You’re looking for the folder that contains libQnnTFLiteDelegate.so for Android arm64.

Find the delegate directory

macOS/Linux (terminal):
```
# replace /opt/qairt/2.xx with your path
find /opt/qairt/2.xx -type f -name libQnnTFLiteDelegate.so
```

Windows:

Open File Explorer on C:\qairt\<version>\

Use the search box for libQnnTFLiteDelegate.so

The parent folder of that file is your source directory (it usually also contains the other libQnn*.so runtime libs).

Create the destination in your project
```
app/src/main/jniLibs/arm64-v8a/
```

Copy the required libraries
From the delegate directory you found in step 2, copy into app/src/main/jniLibs/arm64-v8a/:

Required
```
libQnnTFLiteDelegate.so

All QNN runtimes you need (at minimum the HTP set):

libQnnHtp.so

libQnnHtpV**.so (e.g., V68/V69/V75/V79 — match your device generation)

libQnnHtpV**Skel.so

libQnnSystem.so

libQnnIr.so

libQnnSaver.so (if included in your drop)

libPlatformValidatorShared.so (if included)

Optional

libcdsprpc.so (some devices provide this from /vendor; including it here is harmless)

```
Android + NDK sample showing how to run Edge Impulse inference on-device and enable **Qualcomm® AI Engine Direct (QNN) TFLite Delegate** for acceleration on supported Snapdragon™ devices.

## What’s inside
- Kotlin UI with Camera2 preview + overlay boxes
- JNI bridge to C++ (Edge Impulse SDK + TFLite)
- Optional QNN delegate (HTP/DSP) via shared libs + env vars
- Logcat timing: DSP, classification, anomaly, and end-to-end

## Requirements
- Android Studio (with NDK)
- A device with Snapdragon HTP/DSP (for QNN acceleration)
- Edge Impulse C++ SDK files in `edge-impulse-sdk/`
- TFLite static lib at `tflite/android64/libtensorflow-lite.a`

## Quick start
1. **Clone**

```
   git clone https://github.com/edgeimpulse/qnn-hardware-acceleration.git
```

Open in Android Studio and let Gradle/NDK sync.

Run on a connected device (USB debugging on).

You should see the camera preview with boxes (if using an object detection model) and timing lines in Logcat (MainActivity).

Enable QNN (optional but recommended)

Copy the QNN shared libraries into:

   ```
app/src/main/jniLibs/arm64-v8a/
  libQnnTFLiteDelegate.so
  libQnnHtp*.so
  libQnnHtp*Skel.so
  libQnnSystem.so
  libQnnIr.so
  (plus any other QNN libs for your SoC)
```
### set the manifest

```
<application android:extractNativeLibs="true" ...>
  <uses-native-library android:name="libcdsprpc.so" android:required="false"/>
</application>
```

App sets env vars on startup (no extra steps). If you tweak, ensure:

ADSP_LIBRARY_PATH includes your nativeLibraryDir and /dsp paths

LD_LIBRARY_PATH prepends your nativeLibraryDir

QNN_TFLITE_DELEGATE_OPTIONS uses {"backend":"htp",...}

### Verify acceleration

Logcat (tag MainActivity): look for lower classification_us and dsp_us.

### verify acceleration with the following adb commands checks:
```
adb shell 'pid=$(pidof -s com.example.test_camera); cat /proc/$pid/maps | grep -i qnn'
adb shell ls -l /sdcard/qnn_profile.json
```
## Typical results

Real-world speedups are model-dependent. On a mid-range device we saw:

Classification: ~7.1 ms → ~5.2 ms (~1.4× faster)

DSP stage also improved (~1.7×)

E2E around ~21 ms/frame (~48 FPS) for the sample path

## Troubleshooting 

AAPT error <uses-native-library>: it must be inside <application>.

JNI “not a valid JVM type”: signatures use I/J/F/V (e.g., "(IIIIJJJ)V").

Crashes over time: ensure JNI uses PushLocalFrame/PopLocalFrame and deletes loop locals.

No speed-up: verify libs in jniLibs/arm64-v8a, correct HTP skel version, and env vars.
