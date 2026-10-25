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
                                                               jint height, jint colorspace, jboolean centerChroma) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        return 0;
    }
    std::unique_ptr<PyrowaveRenderer> renderer = PyrowaveRenderer::create(window, width, height, colorspace,
                                                                          centerChroma == JNI_TRUE);
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
