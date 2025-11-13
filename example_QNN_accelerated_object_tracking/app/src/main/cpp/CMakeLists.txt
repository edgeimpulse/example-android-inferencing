# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)


project("test_camera")

set(CMAKE_VERBOSE_MAKEFILE TRUE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -v -stdlib=libc++")

include(edge-impulse-sdk/cmake/utils.cmake)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(EI_SDK_FOLDER edge-impulse-sdk)

# Set TensorFlow Lite library path for 64-bit ARM (arm64-v8a)
set(TFLITE_LIB_DIR ${CMAKE_SOURCE_DIR}/tflite/android64)


#QNN     -DEI_CLASSIFIER_ENABLE_QNN_DELEGATE=1
add_definitions(-DEI_CLASSIFIER_ENABLE_DETECTION_POSTPROCESS_OP=1
    -DEI_CLASSIFIER_USE_FULL_TFLITE=1
    -DEI_CLASSIFIER_ENABLE_QNN_DELEGATE=1
        -DNDEBUG
)

## QNN Deligate enabled -          com.example.test_camera              I  E2E_inference_ms=60.663281
#2025-10-03 18:29:39.837  7610-7610  MainActivity            com.example.test_camera              I  EI_timing_us dsp=5260 cls=11707 anom=0

## QNN Disabled  - -10-03 18:30:42.008  7610-7610  MainActivity            com.example.test_camera              I  EI_timing_us dsp=5714 cls=7070 anom=0
#2025-10-03 18:30:42.020  7610-7610  OpenGLRenderer          com.example.test_camera              D  onFlyCompress
#2025-10-03 18:30:42.050  7610-7677  MainActivity            com.example.test_camera              I  E2E_inference_ms=21.06401
#2025-10-03 18:30:42.050  7610-7610  MainActivity            com.example.test_camera              I  EI_timing_us dsp=3446 cls=5171 anom=0
#2025-10-03 18:30:42.086  7610-7610  OpenGLRenderer          com.example.test_camera              D  onFlyCompress


link_directories(${CMAKE_SOURCE_DIR}/tflite/android64)


add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        native-lib.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        .
)

# Collect C source files
file(GLOB EI_SOURCE_FILES
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/TransformFunctions/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/CommonTables/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/BasicMathFunctions/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/ComplexMathFunctions/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/FastMathFunctions/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/SupportFunctions/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/MatrixFunctions/*.c"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/StatisticsFunctions/*.c"
)

# Collect C++ source files
file(GLOB EI_SOURCE_FILES
        "${CMAKE_SOURCE_DIR}/tflite-model/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/kissfft/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/image/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/dct/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/memory.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/porting/posix/*.c*"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/porting/mingw32/*.c*"
)

LIST(APPEND EI_SOURCE_FILES "${EI_SDK_FOLDER}/tensorflow/lite/c/common.c")

target_sources(${CMAKE_PROJECT_NAME} PRIVATE ${EI_SOURCE_FILES})

target_link_libraries(${CMAKE_PROJECT_NAME}
        ${TFLITE_LIB_DIR}/libtensorflow-lite.a
        android
        log
        farmhash
        fft2d_fftsg
        fft2d_fftsg2d
        ruy
        XNNPACK
        cpuinfo
        pthreadpool)