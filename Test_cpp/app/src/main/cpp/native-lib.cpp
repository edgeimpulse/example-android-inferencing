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
#include <string>
#include <stdio.h>
#include "vector"
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"

std::vector<float> raw_features = {

};

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_test_1cpp_MainActivity_runInference(
        JNIEnv* env,
        jobject) {

    if (raw_features.size() != EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
        printf("The size of your 'features' array is not correct. Expected %d items, but had %d\n",
               EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE, raw_features.size());
        return nullptr;
    }

    ei_impulse_result_t result;

    signal_t signal;
    numpy::signal_from_buffer(&raw_features[0], raw_features.size(), &signal);

    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);

    // Find Java classes
    jclass resultClass = env->FindClass("com/example/test_cpp/InferenceResult");
    jclass timingClass = env->FindClass("com/example/test_cpp/Timing");
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jclass listClass = env->FindClass("java/util/ArrayList");
    jclass boundingBoxClass = env->FindClass("com/example/test_cpp/BoundingBox");

    if (!resultClass || !timingClass || !hashMapClass || !listClass || !boundingBoxClass) {
        return nullptr; // Error finding classes
    }

    // Get method IDs
    jmethodID resultConstructor = env->GetMethodID(resultClass, "<init>",
                                                   "(Ljava/util/Map;Ljava/util/List;F"
                                                   "Lcom/example/test_cpp/Timing;)V");

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

    // Create ArrayList for object detections
    jobject boundingBoxList = env->NewObject(listClass, env->GetMethodID(listClass, "<init>", "()V"));
    for (uint32_t i = 0; i < result.bounding_boxes_count; i++) {
        ei_impulse_result_bounding_box_t bb = result.bounding_boxes[i];
        if (bb.value == 0) continue;

        jstring label = env->NewStringUTF(bb.label);
        jobject boundingBoxObj = env->NewObject(boundingBoxClass,
                                                boundingBoxConstructor,
                                                label,
                                                (jfloat)bb.value,
                                                (jint)bb.x,
                                                (jint)bb.y,
                                                (jint)bb.width,
                                                (jint)bb.height);
        env->CallBooleanMethod(boundingBoxList, listAdd, boundingBoxObj);

        env->DeleteLocalRef(label);
        env->DeleteLocalRef(boundingBoxObj);
    }

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
                                             classificationMap,
                                             boundingBoxList,
                                             result.anomaly,
                                             timingObject);

    return inferenceResult;
}
