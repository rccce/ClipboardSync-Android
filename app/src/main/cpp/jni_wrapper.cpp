#include <jni.h>
#include "clipboard_hook.h"
#include <android/log.h>

#define LOG_TAG "JNIWrapper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeInitializeClipboardHooks(JNIEnv *env, jobject thiz) {
    LOGD("nativeInitializeClipboardHooks called");
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    int result = hook.initialize();
    
    if (result == ClipboardHook::SUCCESS) {
        LOGD("Native clipboard hooks initialized successfully");
    } else {
        LOGE("Failed to initialize native clipboard hooks: %d", result);
    }
    
    return result;
}

JNIEXPORT jint JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeRegisterClipboardCallback(JNIEnv *env, jobject thiz) {
    LOGD("nativeRegisterClipboardCallback called");
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    int result = hook.registerCallback(env, thiz);
    
    if (result == ClipboardHook::SUCCESS) {
        LOGD("Native clipboard callback registered successfully");
    } else {
        LOGE("Failed to register native clipboard callback: %d", result);
    }
    
    return result;
}

JNIEXPORT jint JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeStartClipboardMonitoring(JNIEnv *env, jobject thiz) {
    LOGD("nativeStartClipboardMonitoring called");
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    int result = hook.startMonitoring();
    
    if (result == ClipboardHook::SUCCESS) {
        LOGD("Native clipboard monitoring started successfully");
    } else {
        LOGE("Failed to start native clipboard monitoring: %d", result);
    }
    
    return result;
}

JNIEXPORT jint JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeStopClipboardMonitoring(JNIEnv *env, jobject thiz) {
    LOGD("nativeStopClipboardMonitoring called");
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    int result = hook.stopMonitoring();
    
    if (result == ClipboardHook::SUCCESS) {
        LOGD("Native clipboard monitoring stopped successfully");
    } else {
        LOGE("Failed to stop native clipboard monitoring: %d", result);
    }
    
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeGetClipboardContent(JNIEnv *env, jobject thiz) {
    LOGD("nativeGetClipboardContent called");
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    std::string content = hook.getCurrentClipboardContent();
    
    if (content.empty()) {
        LOGD("No clipboard content available");
        return nullptr;
    }
    
    LOGD("Retrieved clipboard content: %zu bytes", content.length());
    return env->NewStringUTF(content.c_str());
}

JNIEXPORT jint JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeSetClipboardContent(JNIEnv *env, jobject thiz, jstring content) {
    LOGD("nativeSetClipboardContent called");
    
    if (!content) {
        LOGE("Content parameter is null");
        return ClipboardHook::ERROR_INVALID_STATE;
    }
    
    const char* nativeContent = env->GetStringUTFChars(content, nullptr);
    if (!nativeContent) {
        LOGE("Failed to get native string from Java string");
        return ClipboardHook::ERROR_SYSTEM_CALL_FAILED;
    }
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    int result = hook.setClipboardContent(std::string(nativeContent));
    
    env->ReleaseStringUTFChars(content, nativeContent);
    
    if (result == ClipboardHook::SUCCESS) {
        LOGD("Clipboard content set successfully");
    } else {
        LOGE("Failed to set clipboard content: %d", result);
    }
    
    return result;
}

JNIEXPORT jint JNICALL
Java_com_siw_clipboardsync_monitor_NativeClipboardHook_nativeCleanup(JNIEnv *env, jobject thiz) {
    LOGD("nativeCleanup called");
    
    ClipboardHook& hook = ClipboardHook::getInstance();
    int result = hook.cleanup();
    
    if (result == ClipboardHook::SUCCESS) {
        LOGD("Native cleanup completed successfully");
    } else {
        LOGE("Native cleanup failed: %d", result);
    }
    
    return result;
}

} // extern "C"