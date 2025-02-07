/* Edge Impulse Linux SDK
 * Copyright (c) 2021 EdgeImpulse Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <stdio.h>
#include "vector"
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"
#include "edge-impulse-sdk/dsp/image/image.hpp"

jbyte* byteData = nullptr;

static int ei_camera_get_data(size_t offset, size_t length, float *out_ptr)
{
    // we already have a RGB888 buffer, so recalculate offset into pixel index
    size_t pixel_ix = offset * 3;
    size_t pixels_left = length;
    size_t out_ptr_ix = 0;

    while (pixels_left != 0) {

        uint8_t r = static_cast<uint8_t>(byteData[pixel_ix]);
        uint8_t g = static_cast<uint8_t>(byteData[pixel_ix + 1]);
        uint8_t b = static_cast<uint8_t>(byteData[pixel_ix + 2]);

        out_ptr[out_ptr_ix] = (r << 16) + (g << 8) + b;

        // go to the next pixel
        out_ptr_ix++;
        pixel_ix+=3;
        pixels_left--;
    }

    // and done!
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_test_1camera_MainActivity_passToCpp(
        JNIEnv* env,
        jobject,
        jbyteArray image_data) {

    // Get byte array data from JNI
    byteData = env->GetByteArrayElements(image_data, nullptr);
    jsize byteArrayLength = env->GetArrayLength(image_data);

//    if (byteArrayLength != EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
//        __android_log_print(ANDROID_LOG_INFO, "MAIN", "The size of your 'features' array is not correct. Expected %d items, but had %d\n",
//               EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE, byteArrayLength);
//        return env->NewStringUTF("FAIL");
//    }

    ei::image::processing::crop_and_interpolate_rgb888(
            (uint8_t*)byteData,
            480,
            640,
            (uint8_t*)byteData,
            EI_CLASSIFIER_INPUT_WIDTH,
            EI_CLASSIFIER_INPUT_HEIGHT);

    ei_impulse_result_t result;

    signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data = &ei_camera_get_data;

    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);
    std::string res_string = "";

    for (size_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
        res_string.append(result.classification[ix].label);
        res_string.append(" ");
        res_string.append(std::to_string(result.classification[ix].value));
        res_string.append("\n");
    }

    return env->NewStringUTF(res_string.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_test_1camera_MainActivity_passToCppDebug(
        JNIEnv* env,
        jobject,
        jbyteArray image_data) {

    // Get byte array data from JNI
    byteData = env->GetByteArrayElements(image_data, nullptr);
    jsize byteArrayLength = env->GetArrayLength(image_data);

//    if (byteArrayLength != EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
//        __android_log_print(ANDROID_LOG_INFO, "MAIN", "The size of your 'features' array is not correct. Expected %d items, but had %d\n",
//               EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE, byteArrayLength);
//        return env->NewStringUTF("FAIL");
//    }

    ei::image::processing::crop_and_interpolate_rgb888(
            (uint8_t*)byteData,
            480,
            640,
            (uint8_t*)byteData,
            EI_CLASSIFIER_INPUT_WIDTH,
            EI_CLASSIFIER_INPUT_HEIGHT);

    ei_impulse_result_t result;

    signal_t signal;
    float features[EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT];
    ei_camera_get_data(0, EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT, features);
    numpy::signal_from_buffer(features, EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT, &signal);

    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);

    for (size_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "%s : %f", result.classification[ix].label, result.classification[ix].value);
    }
    __android_log_print(ANDROID_LOG_INFO, "MAIN", "\n");

    uint8_t * image_after_signal = new uint8_t[EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT * 3];
    uint32_t pixel_ix = 0;
    for (int i = 0; i < EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT; i++) {
        uint32_t pixel = static_cast<uint32_t>(features[i]);
        image_after_signal[pixel_ix] = ((pixel >> 16) & 0xFF);
        image_after_signal[pixel_ix + 1] = ((pixel >> 8) & 0xFF);
        image_after_signal[pixel_ix + 2] = ((pixel) & 0xFF);
        pixel_ix += 3;
    }

    // Allocate a new byte array to return processed image
    jbyteArray outputArray = env->NewByteArray(EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT * 3);
    if (outputArray == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "MAIN", "Failed to allocate outputArray");
        return nullptr;
    }

    // Copy processed image data to output array
    env->SetByteArrayRegion(outputArray, 0, EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT * 3, (jbyte*)image_after_signal);

    // Release JNI byte array reference
    env->ReleaseByteArrayElements(image_data, byteData, 0);
    delete[] image_after_signal;

    return outputArray;
}