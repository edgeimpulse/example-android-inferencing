# Edge Impulse Hey-Android KWS native build for the Android Data Collector.
# Adapted from example_motion_WearOS/app/src/main/cpp/CMakeLists.txt.

cmake_minimum_required(VERSION 3.22.1)
project("eikws")

set(CMAKE_VERBOSE_MAKEFILE TRUE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -stdlib=libc++")

include(edge-impulse-sdk/cmake/utils.cmake)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(EI_SDK_FOLDER edge-impulse-sdk)

add_definitions(
    -DEI_CLASSIFIER_ENABLE_DETECTION_POSTPROCESS_OP=1
    -DEI_CLASSIFIER_USE_FULL_TFLITE=1
    -DNDEBUG
)

link_directories(${CMAKE_SOURCE_DIR}/tflite/android64)

add_library(${CMAKE_PROJECT_NAME} SHARED
    eikws_native.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    .
)

# Collect C source files (CMSIS DSP — required by the model's DSP block)
file(GLOB EI_C_SOURCES
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/TransformFunctions/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/CommonTables/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/BasicMathFunctions/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/ComplexMathFunctions/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/FastMathFunctions/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/SupportFunctions/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/MatrixFunctions/*.c"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Source/StatisticsFunctions/*.c"
)

file(GLOB EI_CPP_SOURCES
    "${CMAKE_SOURCE_DIR}/tflite-model/*.cpp"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/kissfft/*.cpp"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/dct/*.cpp"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/memory.cpp"
    "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/porting/posix/*.c*"
)

set(EI_SOURCE_FILES ${EI_C_SOURCES} ${EI_CPP_SOURCES})
list(APPEND EI_SOURCE_FILES "${EI_SDK_FOLDER}/tensorflow/lite/c/common.c")

target_sources(${CMAKE_PROJECT_NAME} PRIVATE ${EI_SOURCE_FILES})

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    tensorflow-lite
    farmhash
    fft2d_fftsg
    fft2d_fftsg2d
    ruy
    XNNPACK
    cpuinfo
    pthreadpool
)
