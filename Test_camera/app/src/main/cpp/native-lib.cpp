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
#define CAMERA_INPUT_WIDTH 480
#define CAMERA_INPUT_HEIGHT 640
#define PIXEL_NUM 3

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

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_test_1camera_MainActivity_passToCpp(
        JNIEnv* env,
        jobject,
        jbyteArray image_data) {

    // Get byte array data from JNI
    byteData = env->GetByteArrayElements(image_data, nullptr);
    jsize byteArrayLength = env->GetArrayLength(image_data);

    if (byteArrayLength != CAMERA_INPUT_WIDTH * CAMERA_INPUT_HEIGHT * PIXEL_NUM) {
        __android_log_print(ANDROID_LOG_INFO, "MAIN", "The size of your 'features' array is not correct. Expected %d items, but had %d\n",
                            CAMERA_INPUT_WIDTH * CAMERA_INPUT_HEIGHT * PIXEL_NUM, byteArrayLength);
        return nullptr;
    }

    ei::image::processing::crop_and_interpolate_rgb888(
            (uint8_t*)byteData,
            CAMERA_INPUT_WIDTH,
            CAMERA_INPUT_HEIGHT,
            (uint8_t*)byteData,
            EI_CLASSIFIER_INPUT_WIDTH,
            EI_CLASSIFIER_INPUT_HEIGHT);

    ei_impulse_result_t result;

    signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data = &ei_camera_get_data;

    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);

    // Find Java classes
    jclass resultClass = env->FindClass("com/example/test_camera/InferenceResult");
    jclass timingClass = env->FindClass("com/example/test_camera/Timing");
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jclass listClass = env->FindClass("java/util/ArrayList");
    jclass boundingBoxClass = env->FindClass("com/example/test_camera/BoundingBox");

    if (!resultClass || !timingClass || !hashMapClass || !listClass || !boundingBoxClass) {
        return nullptr; // Error finding classes
    }

    // Get method IDs
    jmethodID resultConstructor = env->GetMethodID(resultClass, "<init>",
                                                   "(Ljava/util/Map;Ljava/util/List;Ljava/util/List;Ljava/util/Map;Lcom/example/test_camera/Timing;)V");

    jmethodID boundingBoxConstructor = env->GetMethodID(boundingBoxClass, "<init>",
                                                        "(Ljava/lang/String;FIIII)V");

    jmethodID timingConstructor = env->GetMethodID(timingClass, "<init>",
                                                   "(IIIIJJJ)V");

    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID hashMapPut = env->GetMethodID(hashMapClass, "put",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    jclass floatClass = env->FindClass("java/lang/Float");
    if (!floatClass) return nullptr;

    // Get Float constructor method ID
    jmethodID floatConstructor = env->GetMethodID(floatClass, "<init>", "(F)V");
    if (!floatConstructor) return nullptr; // Error finding constructor

#if EI_CLASSIFIER_LABEL_COUNT > 0
    // Construct classification map
    jobject classificationMap = env->NewObject(hashMapClass, hashMapInit);
    for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
        jstring key = env->NewStringUTF(result.classification[i].label);
        jobject value = env->NewObject(floatClass, floatConstructor, result.classification[i].value);

        env->CallObjectMethod(classificationMap, hashMapPut, key, value);

        // Cleanup local references
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
#endif

#if EI_CLASSIFIER_OBJECT_DETECTION == 1
    // Create ArrayList for object detections
    jobject boundingBoxList = env->NewObject(listClass, env->GetMethodID(listClass, "<init>", "()V"));
    for (uint32_t i = 0; i < result.bounding_boxes_count; i++) {
        ei_impulse_result_bounding_box_t bb = result.bounding_boxes[i];
        if (bb.value == 0) continue;
        float x_ratio = 1080 / (float)EI_CLASSIFIER_INPUT_WIDTH;
        float y_ratio = 2400 / (float)EI_CLASSIFIER_INPUT_HEIGHT;
        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "x_ratio: %f, y_ratio: %f", x_ratio, y_ratio);

        float x = (float)bb.x * x_ratio;
        float y = (float)bb.y * y_ratio;
        float width = (float)bb.width * x_ratio;
        float height = (float)bb.height * y_ratio;

        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "x: %f, y: %f, width: %f, height: %f", x, y, width, height);

        jstring label = env->NewStringUTF(bb.label);
        jobject boundingBoxObj = env->NewObject(boundingBoxClass,
                                                boundingBoxConstructor,
                                                label,
                                                (jfloat)bb.value,
                                                (jint)x,
                                                (jint)y,
                                                (jint)width,
                                                (jint)height);
        env->CallBooleanMethod(boundingBoxList, listAdd, boundingBoxObj);

        env->DeleteLocalRef(label);
        env->DeleteLocalRef(boundingBoxObj);
    }
#endif

    // Create HashMap for anomaly values
    jobject anomalyResultMap = env->NewObject(hashMapClass, hashMapInit);

#if EI_CLASSIFIER_HAS_ANOMALY != 3
    jobject anomalyString = env->NewStringUTF("anomaly");
    jobject anomalyValue = env->NewObject(floatClass, floatConstructor, (jfloat)result.anomaly);

    env->CallObjectMethod(anomalyResultMap, hashMapPut, anomalyString, anomalyValue);
    env->DeleteLocalRef(anomalyString);
    env->DeleteLocalRef(anomalyValue);
#endif

#if EI_CLASSIFIER_HAS_VISUAL_ANOMALY
    // Create ArrayList for visual anomaly grid cells
    jobject boundingBoxListAnomaly = env->NewObject(listClass, env->GetMethodID(listClass, "<init>", "()V"));
    for (uint32_t i = 0; i < result.visual_ad_count; i++) {
        ei_impulse_result_bounding_box_t bb = result.visual_ad_grid_cells[i];

        float x_ratio = 1080 / (float)EI_CLASSIFIER_INPUT_WIDTH;
        float y_ratio = 2400 / (float)EI_CLASSIFIER_INPUT_HEIGHT;
        //__android_log_print(ANDROID_LOG_INFO, "MAIN", "x_ratio: %f, y_ratio: %f", x_ratio, y_ratio);

        float x = (float)bb.x * x_ratio;
        float y = (float)bb.y * y_ratio;
        float width = (float)bb.width * x_ratio;
        float height = (float)bb.height * y_ratio;

        jstring label = env->NewStringUTF("anomaly");
        jobject boundingBoxObj = env->NewObject(boundingBoxClass,
                                                boundingBoxConstructor,
                                                label,
                                                (jfloat)bb.value,
                                                (jint)x,
                                                (jint)y,
                                                (jint)width,
                                                (jint)height);
        env->CallBooleanMethod(boundingBoxListAnomaly, listAdd, boundingBoxObj);

        env->DeleteLocalRef(label);
        env->DeleteLocalRef(boundingBoxObj);
    }

    jobject maxString = env->NewStringUTF("max");
    jobject maxValue = env->NewObject(floatClass, floatConstructor, (jfloat)result.visual_ad_result.max_value);

    jobject meanString = env->NewStringUTF("mean");
    jobject meanValue = env->NewObject(floatClass, floatConstructor, (jfloat)result.visual_ad_result.mean_value);

    env->CallObjectMethod(anomalyResultMap, hashMapPut, maxString, maxValue);
    env->CallObjectMethod(anomalyResultMap, hashMapPut, meanString, meanValue);
    env->DeleteLocalRef(meanString);
    env->DeleteLocalRef(maxString);
    env->DeleteLocalRef(meanValue);
    env->DeleteLocalRef(maxValue);
#endif

    // Construct Timing object
    jobject timingObject = env->NewObject(timingClass, timingConstructor,
                                          result.timing.sampling,
                                          result.timing.dsp,
                                          result.timing.classification,
                                          result.timing.anomaly,
                                          result.timing.dsp_us,
                                          result.timing.classification_us,
                                          result.timing.anomaly_us);

    // Construct InferenceResult object
    jobject inferenceResult = env->NewObject(resultClass,
                                             resultConstructor,
#if EI_CLASSIFIER_LABEL_COUNT > 0
                                             classificationMap,
#else
            nullptr,
#endif
#if EI_CLASSIFIER_OBJECT_DETECTION == 1
            boundingBoxList,
#else
                                             nullptr,
#endif
#if EI_CLASSIFIER_HAS_VISUAL_ANOMALY
            boundingBoxListAnomaly,
#else
                                             nullptr,
#endif
#if EI_CLASSIFIER_HAS_ANOMALY
            anomalyResultMap,
#else
                                             nullptr,
#endif
                                             timingObject);

    return inferenceResult;
}