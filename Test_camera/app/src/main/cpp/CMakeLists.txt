# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("test_camera")

set(CMAKE_VERBOSE_MAKEFILE TRUE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -v -stdlib=libc++")

include(edge-impulse-sdk/cmake/utils.cmake)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(EI_SDK_FOLDER edge-impulse-sdk)

add_definitions(-DEI_CLASSIFIER_ENABLE_DETECTION_POSTPROCESS_OP=1
    -DEI_CLASSIFIER_USE_FULL_TFLITE=1
    -DNDEBUG
)

link_directories(${CMAKE_SOURCE_DIR}/tflite/android64)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
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

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        log
        tensorflow-lite
        farmhash
        fft2d_fftsg
        fft2d_fftsg2d
        ruy
        XNNPACK
        cpuinfo
        pthreadpool)