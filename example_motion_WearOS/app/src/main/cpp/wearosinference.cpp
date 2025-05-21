/* The Clear BSD License
 *
 * Copyright (c) 2025 EdgeImpulse Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the disclaimer
 * below) provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 *   * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from this
 *   software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY
 * THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

#include <jni.h>
#include <string>
#include <vector>
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"

extern "C"
JNIEXPORT jint JNICALL
Java_com_edgeimpulse_edgeimpulsewearos_presentation_MainActivity_getFeatureCount(JNIEnv* env, jobject /* this */) {
    return EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_edgeimpulse_edgeimpulsewearos_presentation_MainActivity_runInference(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray data // Accelerometer or sensor data
) {
    // 1) Convert jfloatArray -> std::vector<float>
    jsize length = env->GetArrayLength(data);
    jfloat* inputPtr = env->GetFloatArrayElements(data, nullptr);
    std::vector<float> rawFeatures(inputPtr, inputPtr + length);
    env->ReleaseFloatArrayElements(data, inputPtr, 0);

    // 2) Check buffer length matches your EI model's input size
    if (rawFeatures.size() != EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
        std::string errorMsg = "Expected " + std::to_string(EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE)
                               + " floats, but got " + std::to_string(rawFeatures.size());
        return env->NewStringUTF(errorMsg.c_str());
    }

    // 3) Prepare the input signal
    ei_impulse_result_t result;
    signal_t signal;
    numpy::signal_from_buffer(rawFeatures.data(), rawFeatures.size(), &signal);

    // 4) Run the classifier
    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);
    if (res != EI_IMPULSE_OK) {
        std::string errorMsg = "run_classifier returned error code " + std::to_string(res);
        return env->NewStringUTF(errorMsg.c_str());
    }

    // 5) Build a string with classification results
    std::string output = "Classification Results:\n";
    for (uint32_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
        output += result.classification[ix].label;
        output += ": ";
        output += std::to_string(result.classification[ix].value);
        output += "\n";
    }

    // Return as jstring
    return env->NewStringUTF(output.c_str());
}
