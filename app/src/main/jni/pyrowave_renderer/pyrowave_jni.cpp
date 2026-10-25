// JNI for com.limelight.binding.video.PyrowaveRenderer
#include <jni.h>
#include <android/native_window_jni.h>

#include "pyrowave_renderer.h"

using vkr::PyrowaveRenderer;

namespace {
    PyrowaveRenderer* fromHandle(jlong handle) {
        return reinterpret_cast<PyrowaveRenderer*>(handle);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeProbe(JNIEnv*, jclass) {
    return PyrowaveRenderer::probe() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeCreate(JNIEnv* env, jclass, jobject surface, jint width,
                                                               jint height, jint colorspace, jboolean centerChroma,
                                                               jboolean tenBit, jboolean chroma444, jint ditherMode,
                                                               jboolean frontBuffer, jboolean gpuBoost) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        return 0;
    }
    vkr::PyrowaveConfig config;
    config.width = width;
    config.height = height;
    config.colorspace = colorspace;
    config.centerChroma = centerChroma == JNI_TRUE;
    config.tenBit = tenBit == JNI_TRUE;
    config.chroma444 = chroma444 == JNI_TRUE;
    config.ditherMode = ditherMode;
    config.frontBuffer = frontBuffer == JNI_TRUE;
    config.gpuBoost = gpuBoost == JNI_TRUE;
    std::unique_ptr<PyrowaveRenderer> renderer = PyrowaveRenderer::create(window, config);
    // The renderer holds its own reference
    ANativeWindow_release(window);
    return reinterpret_cast<jlong>(renderer.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeSubmit(JNIEnv* env, jclass, jlong handle, jbyteArray data,
                                                               jint length) {
    PyrowaveRenderer* renderer = fromHandle(handle);
    if (!renderer || !data || length <= 0 || length > env->GetArrayLength(data)) {
        return JNI_FALSE;
    }
    // The decode copies what it needs into PyroWave before this returns
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        return JNI_FALSE;
    }
    const bool shown = renderer->submit(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return shown ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeTakeStats(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    PyrowaveRenderer* renderer = fromHandle(handle);
    if (!renderer || !out || env->GetArrayLength(out) < 4) {
        return;
    }
    const vkr::PyrowaveStats stats = renderer->takeStats();
    const jlong values[4] = {stats.decodeNs, stats.decoded, stats.presented, stats.dropped};
    env->SetLongArrayRegion(out, 0, 4, values);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeDescribe(JNIEnv* env, jclass, jlong handle) {
    PyrowaveRenderer* renderer = fromHandle(handle);
    return env->NewStringUTF(renderer ? renderer->describe().c_str() : "");
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeSetHdrMode(JNIEnv* env, jclass, jlong handle, jboolean enabled,
                                                                   jbyteArray metadata) {
    PyrowaveRenderer* renderer = fromHandle(handle);
    if (!renderer) {
        return;
    }
    if (metadata) {
        jsize length = env->GetArrayLength(metadata);
        jbyte* bytes = env->GetByteArrayElements(metadata, nullptr);
        renderer->setHdrMode(enabled == JNI_TRUE, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(length));
        env->ReleaseByteArrayElements(metadata, bytes, JNI_ABORT);
    }
    else {
        renderer->setHdrMode(enabled == JNI_TRUE, nullptr, 0);
    }
}

// 1: front buffer in use, 2: asked for but unavailable, 0: not asked for
extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_PyrowaveRenderer_nativeFrontBufferState(JNIEnv*, jclass, jlong handle) {
    PyrowaveRenderer* renderer = fromHandle(handle);
    if (!renderer) {
        return 0;
    }
    return renderer->frontBufferActive() ? 1 : renderer->frontBufferUnavailable() ? 2 : 0;
}
