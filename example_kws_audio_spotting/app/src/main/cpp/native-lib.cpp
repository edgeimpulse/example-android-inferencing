#include <jni.h>
#include <android/log.h>
#include <string>

#include "edge-impulse-sdk/classifier/ei_run_classifier.h"
#include "model-parameters/model_metadata.h"

#define LOG_TAG "EdgeImpulse"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ---- Model info ----------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_audio_1spotting_MainActivity_getModelInfo(JNIEnv* env, jobject) {
    std::string info = std::string("Model: ") + EI_CLASSIFIER_PROJECT_NAME
                       + " v" + std::to_string(EI_CLASSIFIER_PROJECT_DEPLOY_VERSION)
                       + " • expects " + std::to_string(EI_CLASSIFIER_FREQUENCY) + "Hz / "
                       + std::to_string(EI_CLASSIFIER_RAW_SAMPLE_COUNT) + " samples";
    return env->NewStringUTF(info.c_str());
}

// Slice size for run_classifier_continuous()
JNIEXPORT jint JNICALL
Java_com_example_audio_1spotting_MainActivity_getSliceSize(JNIEnv*, jobject) {
#if defined(EI_CLASSIFIER_SLICES_PER_MODEL_WINDOW) && (EI_CLASSIFIER_SLICES_PER_MODEL_WINDOW > 0)
    const size_t slice = EI_CLASSIFIER_RAW_SAMPLE_COUNT / EI_CLASSIFIER_SLICES_PER_MODEL_WINDOW;
#else
    const size_t slice = EI_CLASSIFIER_RAW_SAMPLE_COUNT; // fallback
#endif
    return static_cast<jint>(slice);
}

// Helper context for copying floats to EI's signal callback
struct SliceCtx {
    float* p; size_t len;
    static int get(size_t off, size_t n, float* out, SliceCtx* c) {
        if (!c || !c->p || off + n > c->len) return -1;
        for (size_t i = 0; i < n; ++i) out[i] = c->p[off + i];
        return 0;
    }
};

// ---- Continuous classifier: one 16kHz float slice [-1..1] ----------------------
JNIEXPORT jfloatArray JNICALL
Java_com_example_audio_1spotting_MainActivity_classifyAudioSlice(
        JNIEnv* env, jobject, jfloatArray raw_slice) {

    jsize n = env->GetArrayLength(raw_slice);
    jfloat* buf = env->GetFloatArrayElements(raw_slice, nullptr);

    SliceCtx ctx{ buf, static_cast<size_t>(n) };
    thread_local SliceCtx* tls = nullptr; // safe per-thread
    tls = &ctx;

    signal_t signal;
    signal.total_length = ctx.len;
    signal.get_data = [](size_t off, size_t len, float* out) -> int {
        return SliceCtx::get(off, len, out, tls);
    };

    ei_impulse_result_t result = {0};
    EI_IMPULSE_ERROR err = run_classifier_continuous(&signal, &result, /*debug=*/false);
    if (err != EI_IMPULSE_OK) {
        LOGE("run_classifier_continuous failed: %d", err);
        env->ReleaseFloatArrayElements(raw_slice, buf, JNI_ABORT);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(EI_CLASSIFIER_LABEL_COUNT);
    if (!out) { env->ReleaseFloatArrayElements(raw_slice, buf, JNI_ABORT); return nullptr; }

#if EI_CLASSIFIER_LABEL_COUNT > 0
    jfloat tmp[EI_CLASSIFIER_LABEL_COUNT];
    for (int i = 0; i < EI_CLASSIFIER_LABEL_COUNT; ++i) tmp[i] = result.classification[i].value;
    env->SetFloatArrayRegion(out, 0, EI_CLASSIFIER_LABEL_COUNT, tmp);
#else
    jfloat zero = 0.f;
    env->SetFloatArrayRegion(out, 0, 1, &zero);
#endif

    env->ReleaseFloatArrayElements(raw_slice, buf, JNI_ABORT);
    return out;
}

// ---- One-shot classifier (compat) ----------------------------------------------
JNIEXPORT jfloatArray JNICALL
Java_com_example_audio_1spotting_MainActivity_classifyAudio(
        JNIEnv* env, jobject, jfloatArray raw_audio) {

    jsize n = env->GetArrayLength(raw_audio);
    jfloat* buf = env->GetFloatArrayElements(raw_audio, nullptr);

    SliceCtx ctx{ buf, static_cast<size_t>(n) };
    thread_local SliceCtx* tls = nullptr;
    tls = &ctx;

    signal_t signal;
    signal.total_length = ctx.len;
    signal.get_data = [](size_t off, size_t len, float* out) -> int {
        return SliceCtx::get(off, len, out, tls);
    };

    ei_impulse_result_t result = {0};
    EI_IMPULSE_ERROR err = run_classifier(&signal, &result, /*debug=*/false);
    if (err != EI_IMPULSE_OK) {
        LOGE("run_classifier failed: %d", err);
        env->ReleaseFloatArrayElements(raw_audio, buf, JNI_ABORT);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(EI_CLASSIFIER_LABEL_COUNT);
    if (!out) { env->ReleaseFloatArrayElements(raw_audio, buf, JNI_ABORT); return nullptr; }

#if EI_CLASSIFIER_LABEL_COUNT > 0
    jfloat tmp[EI_CLASSIFIER_LABEL_COUNT];
    for (int i = 0; i < EI_CLASSIFIER_LABEL_COUNT; ++i) tmp[i] = result.classification[i].value;
    env->SetFloatArrayRegion(out, 0, EI_CLASSIFIER_LABEL_COUNT, tmp);
#else
    jfloat zero = 0.f;
    env->SetFloatArrayRegion(out, 0, 1, &zero);
#endif

    env->ReleaseFloatArrayElements(raw_audio, buf, JNI_ABORT);
    return out;
}
} // extern "C"
