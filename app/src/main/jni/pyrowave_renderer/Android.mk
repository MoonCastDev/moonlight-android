# MoonCast's PyroWave renderer (see pyrowave_renderer.h). libpyrowave-shared.so, the codec itself,
# is a prebuilt (../pyrowave, arm64-v8a only) loaded at runtime, so this builds for every ABI and
# PyroWave just isn't offered where the library or a capable Vulkan device is missing.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := mooncast-pyrowave
LOCAL_SRC_FILES := \
    gpu_keep_busy.cpp \
    pyrowave_decoder.cpp \
    pyrowave_jni.cpp \
    pyrowave_renderer.cpp \
    vk_api.cpp \

LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CPPFLAGS += -std=c++17 -Wall

# Vulkan is loaded at runtime (see vk_api.h), since the app's native code is built for API 21
LOCAL_LDLIBS := -llog -landroid -ldl

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_SHARED_LIBRARY)
