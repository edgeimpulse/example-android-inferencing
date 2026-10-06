#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstdlib>   // setenv
#include <cstdint>

#include "edge-impulse-sdk/classifier/ei_run_classifier.h"
#include "edge-impulse-sdk/dsp/image/image.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "MAIN", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MAIN", __VA_ARGS__)

// Camera RGB888 buffer from Kotlin: 640x480
#define CAMERA_INPUT_WIDTH   640
#define CAMERA_INPUT_HEIGHT  480
#define PIXEL_NUM            3

static jbyte* g_rgb = nullptr;

// Convert packed RGB888 to EI's expected pixel value (r<<16 | g<<8 | b)
static int ei_camera_get_data(size_t offset, size_t length, float *out_ptr) {
    size_t pixel_ix = offset * PIXEL_NUM;
    for (size_t i = 0; i < length; ++i) {
        uint8_t r = static_cast<uint8_t>(g_rgb[pixel_ix    ]);
        uint8_t g = static_cast<uint8_t>(g_rgb[pixel_ix + 1]);
        uint8_t b = static_cast<uint8_t>(g_rgb[pixel_ix + 2]);
        out_ptr[i] = (r << 16) + (g << 8) + b;
        pixel_ix += PIXEL_NUM;
    }
    return 0;
}

// ==== JNI: env setter (member of MainActivity; Kotlin method must be a member) ====
extern "C" JNIEXPORT jint JNICALL
Java_com_example_test_1camera_MainActivity_setEnvVar(
        JNIEnv* env, jobject /*thiz*/, jstring jname, jstring jval) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    const char* val  = env->GetStringUTFChars(jval,  nullptr);
    int rc = setenv(name, val, 1);
    LOGI("setenv('%s','%s')->%d", name, val, rc);
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jval,  val);
    return rc;
}

// ==== JNI: inference (scales boxes to overlayW/H; correct JVM signatures) ====
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_test_1camera_MainActivity_passToCpp(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray image_data, jint overlay_w, jint overlay_h) {

    // Acquire RGB888 buffer from Java
    g_rgb = env->GetByteArrayElements(image_data, nullptr);
    const jsize byteCount = env->GetArrayLength(image_data);
    const jsize expected  = CAMERA_INPUT_WIDTH * CAMERA_INPUT_HEIGHT * PIXEL_NUM;
    if (byteCount != expected) {
        LOGE("Bad features size: expected %d, got %d", expected, (int)byteCount);
        env->ReleaseByteArrayElements(image_data, g_rgb, 0);
        return nullptr;
    }

    // Resize to model input
    ei::image::processing::crop_and_interpolate_rgb888(
            reinterpret_cast<uint8_t*>(g_rgb), CAMERA_INPUT_WIDTH, CAMERA_INPUT_HEIGHT,
            reinterpret_cast<uint8_t*>(g_rgb), EI_CLASSIFIER_INPUT_WIDTH, EI_CLASSIFIER_INPUT_HEIGHT);

    // Run classifier
    ei_impulse_result_t result;
    signal_t signal;
    signal.total_length = EI_CLASSIFIER_INPUT_WIDTH * EI_CLASSIFIER_INPUT_HEIGHT;
    signal.get_data     = &ei_camera_get_data;

    EI_IMPULSE_ERROR rc = run_classifier(&signal, &result, false);
    if (rc != EI_IMPULSE_OK) {
        LOGE("run_classifier rc=%d", (int)rc);
        env->ReleaseByteArrayElements(image_data, g_rgb, 0);
        return nullptr;
    }

    // --- Build Java return types ---
    jclass resultCls    = env->FindClass("com/example/test_camera/InferenceResult");
    jclass timingCls    = env->FindClass("com/example/test_camera/Timing");
    jclass bboxCls      = env->FindClass("com/example/test_camera/BoundingBox");
    jclass hashMapCls   = env->FindClass("java/util/HashMap");
    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jclass floatCls     = env->FindClass("java/lang/Float");

    if (!resultCls || !timingCls || !bboxCls || !hashMapCls || !arrayListCls || !floatCls) {
        env->ReleaseByteArrayElements(image_data, g_rgb, 0);
        return nullptr;
    }

    // Correct JVM signatures (no 'jint'/'jlong' inside the strings)
    jmethodID resultCtor = env->GetMethodID(
            resultCls, "<init>",
            "(Ljava/util/Map;Ljava/util/List;Ljava/util/List;Ljava/util/Map;Lcom/example/test_camera/Timing;)V");
    jmethodID timingCtor = env->GetMethodID(timingCls, "<init>", "(IIIIJJJ)V");  // 4 ints, 3 longs
    jmethodID bboxCtor   = env->GetMethodID(bboxCls,   "<init>", "(Ljava/lang/String;FIIII)V");
    jmethodID mapInit    = env->GetMethodID(hashMapCls,   "<init>", "()V");
    jmethodID mapPut     = env->GetMethodID(hashMapCls,   "put",   "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jmethodID listInit   = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID listAdd    = env->GetMethodID(arrayListCls, "add",   "(Ljava/lang/Object;)Z");
    jmethodID floatCtor  = env->GetMethodID(floatCls,     "<init>", "(F)V");

    // classification map
    jobject classificationMap = nullptr;
#if EI_CLASSIFIER_LABEL_COUNT > 0
    classificationMap = env->NewObject(hashMapCls, mapInit);
    for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; ++i) {
        jstring key = env->NewStringUTF(result.classification[i].label);
        jobject val = env->NewObject(floatCls, floatCtor, (jfloat)result.classification[i].value);
        env->CallObjectMethod(classificationMap, mapPut, key, val);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(val);
    }
#endif

    // detections scaled to overlay
    jobject bboxList = nullptr;
#if EI_CLASSIFIER_OBJECT_DETECTION == 1
    bboxList = env->NewObject(arrayListCls, listInit);
    const float sx = overlay_w / (float)EI_CLASSIFIER_INPUT_WIDTH;
    const float sy = overlay_h / (float)EI_CLASSIFIER_INPUT_HEIGHT;

    for (uint32_t i = 0; i < result.bounding_boxes_count; ++i) {
        const ei_impulse_result_bounding_box_t& bb = result.bounding_boxes[i];
        if (bb.value == 0) continue;

        const int x = (int)(bb.x      * sx);
        const int y = (int)(bb.y      * sy);
        const int w = (int)(bb.width  * sx);
        const int h = (int)(bb.height * sy);

        jstring label = env->NewStringUTF(bb.label);
        jobject one   = env->NewObject(bboxCls, bboxCtor, label,
                                       (jfloat)bb.value, (int)x, (int)y, (int)w, (int)h);
        env->CallBooleanMethod(bboxList, listAdd, one);
        env->DeleteLocalRef(label);
        env->DeleteLocalRef(one);
    }
#endif

    // visual anomaly cells (optional)
    jobject vaList = nullptr;
#if EI_CLASSIFIER_HAS_VISUAL_ANOMALY
    vaList = env->NewObject(arrayListCls, listInit);
    const float sxa = overlay_w / (float)EI_CLASSIFIER_INPUT_WIDTH;
    const float sya = overlay_h / (float)EI_CLASSIFIER_INPUT_HEIGHT;
    for (uint32_t i = 0; i < result.visual_ad_count; ++i) {
        const ei_impulse_result_bounding_box_t& bb = result.visual_ad_grid_cells[i];
        const int x = (int)(bb.x      * sxa);
        const int y = (int)(bb.y      * sya);
        const int w = (int)(bb.width  * sxa);
        const int h = (int)(bb.height * sya);
        jstring label = env->NewStringUTF("anomaly");
        jobject one   = env->NewObject(bboxCls, bboxCtor, label,
                                       (jfloat)bb.value, (jint)x, (jint)y, (jint)w, (jint)h);
        env->CallBooleanMethod(vaList, listAdd, one);
        env->DeleteLocalRef(label);
        env->DeleteLocalRef(one);
    }
#endif

    // anomaly map
    jobject anomalyMap = env->NewObject(hashMapCls, mapInit);
#if EI_CLASSIFIER_HAS_ANOMALY
    jstring aKey = env->NewStringUTF("anomaly");
    jobject aVal = env->NewObject(floatCls, floatCtor, (jfloat)result.anomaly);
    env->CallObjectMethod(anomalyMap, mapPut, aKey, aVal);
    env->DeleteLocalRef(aKey);
    env->DeleteLocalRef(aVal);
#endif

    // timing (cast values; signature uses I and J, not 'jint'/'jlong')
    jobject timingObj = env->NewObject(
            timingCls, timingCtor,
            (int)result.timing.sampling,
            (int)result.timing.dsp,
            (int)result.timing.classification,
            (int)result.timing.anomaly,
            (long)result.timing.dsp_us,
            (long)result.timing.classification_us,
            (long)result.timing.anomaly_us
    );

    // final result
    jobject ret = env->NewObject(
            resultCls, resultCtor,
            classificationMap, bboxList, vaList, anomalyMap, timingObj
    );

    env->ReleaseByteArrayElements(image_data, g_rgb, 0);
    return ret;
}
