cmake_minimum_required(VERSION 3.22.1)
project("audiospot")  # must match System.loadLibrary("audiospot")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Faster builds / smaller symbols
add_compile_options(-O3 -ffast-math -fvisibility=hidden)

include(edge-impulse-sdk/cmake/utils.cmake)

add_definitions(
        -DEI_CLASSIFIER_ENABLE_DETECTION_POSTPROCESS_OP=1
        -DEI_CLASSIFIER_USE_FULL_TFLITE=1
        -DNDEBUG
)

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

# Includes (TensorFlow Lite headers are vendored by EI’s SDK)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_SOURCE_DIR}
        ${CMAKE_SOURCE_DIR}/edge-impulse-sdk
        ${CMAKE_SOURCE_DIR}/edge-impulse-sdk/tensorflow
        ${CMAKE_SOURCE_DIR}/edge-impulse-sdk/CMSIS/DSP/Include
        ${CMAKE_SOURCE_DIR}/edge-impulse-sdk/third_party/flatbuffers/include
)

# Sources (audio only)
file(GLOB EI_SOURCE_FILES
        "${CMAKE_SOURCE_DIR}/tflite-model/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/kissfft/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/dct/*.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/dsp/memory.cpp"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/porting/posix/*.c*"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/porting/mingw32/*.c*"
        "${CMAKE_SOURCE_DIR}/edge-impulse-sdk/tensorflow/lite/c/common.c"
)
target_sources(${CMAKE_PROJECT_NAME} PRIVATE ${EI_SOURCE_FILES})

# Prebuilt TFLite libs (downloaded with the provided script)
set(TFLITE_LIB_DIR "${CMAKE_SOURCE_DIR}/tflite/android64")

add_library(tensorflow-lite STATIC IMPORTED)
set_target_properties(tensorflow-lite PROPERTIES
        IMPORTED_LOCATION "${TFLITE_LIB_DIR}/libtensorflow-lite.a"
)

foreach(LIB IN ITEMS farmhash fft2d_fftsg fft2d_fftsg2d ruy XNNPACK cpuinfo pthreadpool flatbuffers)
    add_library(${LIB} STATIC IMPORTED)
    set_target_properties(${LIB} PROPERTIES IMPORTED_LOCATION "${TFLITE_LIB_DIR}/lib${LIB}.a")
endforeach()

target_link_libraries(${CMAKE_PROJECT_NAME}
        android log
        tensorflow-lite
        farmhash fft2d_fftsg fft2d_fftsg2d ruy XNNPACK cpuinfo pthreadpool flatbuffers
)
