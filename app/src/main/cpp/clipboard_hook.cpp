#include "clipboard_hook.h"
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <chrono>
#include <mutex>

#define LOG_TAG "NativeClipboardHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global instance
static std::unique_ptr<ClipboardHook> g_clipboardHook;
static std::mutex g_instanceMutex;

ClipboardHook::ClipboardHook() {
    LOGD("ClipboardHook constructor called");
}

ClipboardHook::~ClipboardHook() {
    LOGD("ClipboardHook destructor called");
    cleanup();
}

ClipboardHook& ClipboardHook::getInstance() {
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    if (!g_clipboardHook) {
        g_clipboardHook = std::make_unique<ClipboardHook>();
    }
    return *g_clipboardHook;
}

int ClipboardHook::initialize() {
    std::lock_guard<std::mutex> lock(hookMutex_);
    
    if (initialized_.load()) {
        LOGD("ClipboardHook already initialized");
        return SUCCESS;
    }
    
    LOGD("Initializing ClipboardHook");
    
    // Check for root access
    if (!checkRootAccess()) {
        LOGE("Root access not available, cannot initialize system-level hooks");
        return ERROR_PERMISSION_DENIED;
    }
    
    // Setup system hooks
    int result = setupSystemHooks();
    if (result != SUCCESS) {
        LOGE("Failed to setup system hooks: %d", result);
        return result;
    }
    
    initialized_.store(true);
    LOGI("ClipboardHook initialized successfully");
    return SUCCESS;
}

int ClipboardHook::registerCallback(JNIEnv* env, jobject javaObject) {
    std::lock_guard<std::mutex> lock(hookMutex_);
    
    if (!initialized_.load()) {
        LOGE("ClipboardHook not initialized");
        return ERROR_INVALID_STATE;
    }
    
    // Get JavaVM reference
    if (env->GetJavaVM(&jvm_) != JNI_OK) {
        LOGE("Failed to get JavaVM reference");
        return ERROR_INITIALIZATION_FAILED;
    }
    
    // Create global reference to Java callback object
    javaCallbackObject_ = env->NewGlobalRef(javaObject);
    if (!javaCallbackObject_) {
        LOGE("Failed to create global reference to Java callback object");
        return ERROR_INITIALIZATION_FAILED;
    }
    
    // Get method IDs for callbacks
    jclass clazz = env->GetObjectClass(javaObject);
    onClipboardChangedMethod_ = env->GetMethodID(clazz, "onClipboardChanged", "(Ljava/lang/String;J)V");
    onNativeErrorMethod_ = env->GetMethodID(clazz, "onNativeError", "(ILjava/lang/String;)V");
    
    if (!onClipboardChangedMethod_ || !onNativeErrorMethod_) {
        LOGE("Failed to get callback method IDs");
        env->DeleteGlobalRef(javaCallbackObject_);
        javaCallbackObject_ = nullptr;
        return ERROR_INITIALIZATION_FAILED;
    }
    
    LOGD("Callback registered successfully");
    return SUCCESS;
}

int ClipboardHook::startMonitoring() {
    std::lock_guard<std::mutex> lock(hookMutex_);
    
    if (!initialized_.load()) {
        LOGE("ClipboardHook not initialized");
        return ERROR_INVALID_STATE;
    }
    
    if (monitoring_.load()) {
        LOGD("Monitoring already active");
        return SUCCESS;
    }
    
    LOGD("Starting clipboard monitoring");
    
    int result = startSystemLevelMonitoring();
    if (result != SUCCESS) {
        LOGE("Failed to start system-level monitoring: %d", result);
        return result;
    }
    
    monitoring_.store(true);
    LOGI("Clipboard monitoring started successfully");
    return SUCCESS;
}

int ClipboardHook::stopMonitoring() {
    std::lock_guard<std::mutex> lock(hookMutex_);
    
    if (!monitoring_.load()) {
        LOGD("Monitoring not active");
        return SUCCESS;
    }
    
    LOGD("Stopping clipboard monitoring");
    
    int result = stopSystemLevelMonitoring();
    monitoring_.store(false);
    
    LOGI("Clipboard monitoring stopped");
    return result;
}

std::string ClipboardHook::getCurrentClipboardContent() {
    if (!initialized_.load()) {
        LOGE("ClipboardHook not initialized");
        return "";
    }
    
    // Use system command to get clipboard content
    std::string command = "su -c 'service call clipboard 2 s16 com.android.shell'";
    
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) {
        LOGE("Failed to execute clipboard read command");
        return "";
    }
    
    char buffer[1024];
    std::string result;
    
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer;
    }
    
    pclose(pipe);
    
    // Parse the result to extract actual clipboard content
    // This is a simplified implementation - real implementation would need
    // proper parsing of the service call output
    return result;
}

int ClipboardHook::setClipboardContent(const std::string& content) {
    if (!initialized_.load()) {
        LOGE("ClipboardHook not initialized");
        return ERROR_INVALID_STATE;
    }
    
    // Escape content for shell command
    std::string escapedContent = content;
    // Simple escaping - real implementation would need comprehensive escaping
    size_t pos = 0;
    while ((pos = escapedContent.find("'", pos)) != std::string::npos) {
        escapedContent.replace(pos, 1, "\\'");
        pos += 2;
    }
    
    std::string command = "su -c 'am broadcast -a clipper.set -e text \"" + escapedContent + "\"'";
    
    int result = executeRootCommand(command);
    if (result != 0) {
        LOGE("Failed to set clipboard content via root command");
        return ERROR_SYSTEM_CALL_FAILED;
    }
    
    return SUCCESS;
}

int ClipboardHook::cleanup() {
    std::lock_guard<std::mutex> lock(hookMutex_);
    
    LOGD("Cleaning up ClipboardHook");
    
    stopMonitoring();
    teardownSystemHooks();
    
    // Clean up JNI references
    if (javaCallbackObject_ && jvm_) {
        JNIEnv* env;
        if (jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(javaCallbackObject_);
        }
        javaCallbackObject_ = nullptr;
    }
    
    initialized_.store(false);
    LOGI("ClipboardHook cleanup completed");
    return SUCCESS;
}

bool ClipboardHook::checkRootAccess() {
    // Check if su binary exists and is executable
    if (access("/system/bin/su", X_OK) == 0 || 
        access("/system/xbin/su", X_OK) == 0 ||
        access("/su/bin/su", X_OK) == 0) {
        
        // Try to execute a simple su command
        int result = executeRootCommand("su -c 'echo test'");
        return result == 0;
    }
    
    return false;
}

int ClipboardHook::executeRootCommand(const std::string& command) {
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) {
        LOGE("Failed to execute root command: %s", command.c_str());
        return -1;
    }
    
    int status = pclose(pipe);
    return WEXITSTATUS(status);
}

int ClipboardHook::setupSystemHooks() {
    LOGD("Setting up system hooks");
    
    // In a real implementation, this would:
    // 1. Hook into the ClipboardService using native methods
    // 2. Register callbacks for clipboard change events
    // 3. Set up monitoring threads or event listeners
    
    // For this implementation, we'll simulate the setup
    systemHookHandle_ = reinterpret_cast<void*>(0x12345678); // Dummy handle
    
    LOGD("System hooks setup completed");
    return SUCCESS;
}

int ClipboardHook::teardownSystemHooks() {
    LOGD("Tearing down system hooks");
    
    if (systemHookHandle_) {
        // Clean up system hooks
        systemHookHandle_ = nullptr;
    }
    
    LOGD("System hooks teardown completed");
    return SUCCESS;
}

int ClipboardHook::startSystemLevelMonitoring() {
    LOGD("Starting system-level monitoring");
    
    // In a real implementation, this would start the actual monitoring
    // For now, we'll simulate it with a background thread that periodically
    // checks for clipboard changes
    
    std::thread monitoringThread([this]() {
        std::string lastContent;
        
        while (monitoring_.load()) {
            std::string currentContent = getCurrentClipboardContent();
            
            if (!currentContent.empty() && currentContent != lastContent) {
                lastContent = currentContent;
                int64_t timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch()).count();
                
                notifyClipboardChange(currentContent, timestamp);
            }
            
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    });
    
    monitoringThread.detach();
    
    LOGD("System-level monitoring started");
    return SUCCESS;
}

int ClipboardHook::stopSystemLevelMonitoring() {
    LOGD("Stopping system-level monitoring");
    
    // The monitoring thread will stop when monitoring_ is set to false
    // which happens in stopMonitoring()
    
    LOGD("System-level monitoring stopped");
    return SUCCESS;
}

void ClipboardHook::notifyClipboardChange(const std::string& content, int64_t timestamp) {
    if (!javaCallbackObject_ || !jvm_) {
        LOGE("Java callback not available");
        return;
    }
    
    JNIEnv* env;
    if (jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNI environment");
        return;
    }
    
    jstring jContent = env->NewStringUTF(content.c_str());
    if (jContent) {
        env->CallVoidMethod(javaCallbackObject_, onClipboardChangedMethod_, jContent, timestamp);
        env->DeleteLocalRef(jContent);
    }
    
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Exception occurred in Java callback");
    }
}

void ClipboardHook::notifyError(int errorCode, const std::string& message) {
    if (!javaCallbackObject_ || !jvm_) {
        LOGE("Java callback not available for error notification");
        return;
    }
    
    JNIEnv* env;
    if (jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNI environment for error notification");
        return;
    }
    
    jstring jMessage = env->NewStringUTF(message.c_str());
    if (jMessage) {
        env->CallVoidMethod(javaCallbackObject_, onNativeErrorMethod_, errorCode, jMessage);
        env->DeleteLocalRef(jMessage);
    }
    
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Exception occurred in error callback");
    }
}

// C-style callbacks for system hooks
extern "C" {
    void clipboard_change_callback(const char* content, int64_t timestamp) {
        ClipboardHook& hook = ClipboardHook::getInstance();
        hook.notifyClipboardChange(std::string(content), timestamp);
    }
    
    void clipboard_error_callback(int error_code, const char* message) {
        ClipboardHook& hook = ClipboardHook::getInstance();
        hook.notifyError(error_code, std::string(message));
    }
}