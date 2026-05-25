/* Edge Impulse "hey_android" KWS JNI bridge for the Android Data Collector.
 *
 * Exposes a small continuous-classifier API to Kotlin:
 *   init()             – calls run_classifier_init()
 *   sliceSize()        – samples per slice (PCM 16k mono)
 *   labelCount()       – number of output labels
 *   label(i)           – label name at index i
 *   runSlice(float[])  – feed one slice, returns float[label_count] scores
 *   deinit()           – tear down
 */

#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>

#include "edge-impulse-sdk/classifier/ei_run_classifier.h"

#define LOG_TAG "EiKwsNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_lock;
    bool g_inited = false;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_initNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (g_inited) return 0;
    run_classifier_init();
    g_inited = true;
    LOGI("run_classifier_init() done; slice=%d freq=%d labels=%d",
         (int)EI_CLASSIFIER_SLICE_SIZE,
         (int)EI_CLASSIFIER_FREQUENCY,
         (int)EI_CLASSIFIER_LABEL_COUNT);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_deinitNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_inited) return;
    run_classifier_deinit();
    g_inited = false;
}

JNIEXPORT jint JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_sliceSize(JNIEnv*, jobject) {
    return (jint)EI_CLASSIFIER_SLICE_SIZE;
}

JNIEXPORT jint JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_frequency(JNIEnv*, jobject) {
    return (jint)EI_CLASSIFIER_FREQUENCY;
}

JNIEXPORT jint JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_labelCount(JNIEnv*, jobject) {
    return (jint)EI_CLASSIFIER_LABEL_COUNT;
}

JNIEXPORT jstring JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_label(JNIEnv* env, jobject, jint idx) {
    if (idx < 0 || idx >= (jint)EI_CLASSIFIER_LABEL_COUNT) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(ei_classifier_inferencing_categories[idx]);
}

/**
 * Feed one slice of PCM-as-float (range roughly +/-32768 like int16) and return
 * the post-MAF classification scores.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_edgeimpulse_gattsensors_voice_KwsNative_runSlice(JNIEnv* env, jobject,
                                                          jfloatArray data) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_inited) {
        run_classifier_init();
        g_inited = true;
    }

    jsize length = env->GetArrayLength(data);
    if (length != (jsize)EI_CLASSIFIER_SLICE_SIZE) {
        LOGE("runSlice: expected %d samples, got %d",
             (int)EI_CLASSIFIER_SLICE_SIZE, (int)length);
        return nullptr;
    }

    jfloat* inputPtr = env->GetFloatArrayElements(data, nullptr);
    static std::vector<float> g_slice(EI_CLASSIFIER_SLICE_SIZE);
    std::copy(inputPtr, inputPtr + length, g_slice.begin());
    env->ReleaseFloatArrayElements(data, inputPtr, JNI_ABORT);

    signal_t signal;
    int err = numpy::signal_from_buffer(g_slice.data(), g_slice.size(), &signal);
    if (err != 0) {
        LOGE("signal_from_buffer failed: %d", err);
        return nullptr;
    }

    ei_impulse_result_t result = { 0 };
    EI_IMPULSE_ERROR res = run_classifier_continuous(&signal, &result,
                                                     /*debug*/ false,
                                                     /*enable_maf*/ true);
    if (res != EI_IMPULSE_OK) {
        LOGE("run_classifier_continuous err=%d", res);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(EI_CLASSIFIER_LABEL_COUNT);
    std::vector<float> scores(EI_CLASSIFIER_LABEL_COUNT);
    for (uint32_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
        scores[i] = result.classification[i].value;
    }
    env->SetFloatArrayRegion(out, 0, EI_CLASSIFIER_LABEL_COUNT, scores.data());
    return out;
}

} // extern "C"
