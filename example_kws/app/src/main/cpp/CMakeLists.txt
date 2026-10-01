# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("audiospot")

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
