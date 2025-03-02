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
