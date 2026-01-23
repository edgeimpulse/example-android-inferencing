cmake_minimum_required(VERSION 3.10)

# Set the project name
project(speech_to_image-native)

# Set the C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the source files
set(SOURCE_FILES
        speech_to_image-native.cpp
        src/Log/AndroidLogger.cpp
        src/Log/Logger.cpp
        src/Log/LogUtils.cpp
        src/PAL/src/linux/DynamicLoading.cpp
        src/PAL/src/linux/Directory.cpp
        src/PAL/src/linux/FileOp.cpp
        src/PAL/src/linux/Path.cpp
        src/PAL/src/common/StringOp.cpp
        src/PAL/src/common/GetOpt.cpp
        src/QnnSpeechToImage.cpp
        src/SpeechToImage.cpp
        src/Utils/DataUtil.cpp
        src/Utils/DynamicLoadUtil.cpp
        src/Utils/IOTensor.cpp
        src/Utils/QnnSpeechToImageUtils.cpp
        src/Utils/raw_image_to_png.cpp
        src/WrapperUtils/QnnWrapperUtils.cpp
        src/LogMel/src/log_mel_spectrogram.cpp
        src/LogMel/src/wavreader.cpp
        src/LogMel/src/preprocess.cpp
        src/float16.cpp
)

# Create the shared library
add_library(speech_to_image-native SHARED ${SOURCE_FILES})


set(QNN_SDK_ROOT $ENV{QNN_SDK_ROOT})

# Include directories for header files
include_directories(
        ./
        ./src
        ./src/Log
        ./src/PAL/include
        ./src/PAL/include/PAL
        ./src/Utils
        ./src/WrapperUtils
        ./src/LogMel/include
        ./src//QNN
)

target_link_libraries(speech_to_image-native
        android
        log
        GLESv2
        EGL
        c
        m
        dl
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnHtp.so
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnHtpPrepare.so
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnSystem.so
        ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libc++_shared.so
  )


