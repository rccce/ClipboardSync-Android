#include "clipboard_hook.h"
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <chrono>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <memory>

#define LOG_TAG "NativeClipboardHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global instance
static std::unique_ptr<ClipboardHook> g_clipboardHook;
static std::mutex g_instanceMutex;

// Thread-safe monitoring state
static std::atomic<bool> g_monitoringThreadRunning{false};
static std::thread g_monitoringThread;
static std::condition_variable g_monitoringCV;
static std::mutex g_monitoringMutex;

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

/**
 * Destroys the global instance and releases all resources.
 * Requirements: 7.5
 */
void ClipboardHook::destroyInstance() {
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    if (g_clipboardHook) {
        g_clipboardHook->cleanup();
        g_clipboardHook.reset();
    }
    LOGI("ClipboardHook instance destroyed");
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
        notifyError(ERROR_PERMISSION_DENIED, "Root access not available");
        return ERROR_PERMISSION_DENIED;
    }
    
    // Setup system hooks
    int result = setupSystemHooks();
    if (result != SUCCESS) {
        LOGE("Failed to setup system hooks: %d", result);
        notifyError(result, "Failed to setup system hooks");
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
    
    // Clean up existing callback if any
    cleanupJniReferences(env);
    
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
    if (!clazz) {
        LOGE("Failed to get object class");
        cleanupJniReferences(env);
        return ERROR_INITIALIZATION_FAILED;
    }
    
    onClipboardChangedMethod_ = env->GetMethodID(clazz, "onClipboardChanged", "(Ljava/lang/String;J)V");
    onNativeErrorMethod_ = env->GetMethodID(clazz, "onNativeError", "(ILjava/lang/String;)V");
    
    if (!onClipboardChangedMethod_ || !onNativeErrorMethod_) {
        LOGE("Failed to get callback method IDs");
        cleanupJniReferences(env);
        return ERROR_INITIALIZATION_FAILED;
    }
    
    LOGD("Callback registered successfully");
    return SUCCESS;
}

/**
 * Cleans up JNI references safely.
 * Requirements: 7.5
 */
void ClipboardHook::cleanupJniReferences(JNIEnv* env) {
    if (javaCallbackObject_) {
        if (env) {
            env->DeleteGlobalRef(javaCallbackObject_);
        } else if (jvm_) {
            JNIEnv* localEnv;
            bool needsDetach = false;
            
            if (jvm_->GetEnv(reinterpret_cast<void**>(&localEnv), JNI_VERSION_1_6) == JNI_EDETACHED) {
                if (jvm_->AttachCurrentThread(&localEnv, nullptr) == JNI_OK) {
                    needsDetach = true;
                } else {
                    LOGW("Failed to attach thread for JNI cleanup");
                    return;
                }
            }
            
            if (localEnv) {
                localEnv->DeleteGlobalRef(javaCallbackObject_);
            }
            
            if (needsDetach) {
                jvm_->DetachCurrentThread();
            }
        }
        javaCallbackObject_ = nullptr;
    }
    
    onClipboardChangedMethod_ = nullptr;
    onNativeErrorMethod_ = nullptr;
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
        notifyError(result, "Failed to start monitoring");
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
        notifyError(ERROR_SYSTEM_CALL_FAILED, "Failed to execute clipboard read command");
        return "";
    }
    
    char buffer[1024];
    std::string result;
    
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer;
    }
    
    int status = pclose(pipe);
    if (WEXITSTATUS(status) != 0) {
        LOGW("Clipboard read command returned non-zero status: %d", WEXITSTATUS(status));
    }
    
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
        notifyError(ERROR_SYSTEM_CALL_FAILED, "Failed to set clipboard content");
        return ERROR_SYSTEM_CALL_FAILED;
    }
    
    return SUCCESS;
}

int ClipboardHook::cleanup() {
    std::lock_guard<std::mutex> lock(hookMutex_);
    
    LOGD("Cleaning up ClipboardHook");
    
    // Stop monitoring first
    if (monitoring_.load()) {
        monitoring_.store(false);
        stopSystemLevelMonitoring();
    }
    
    // Wait for monitoring thread to finish
    waitForMonitoringThreadStop();
    
    teardownSystemHooks();
    
    // Clean up JNI references
    cleanupJniReferences(nullptr);
    jvm_ = nullptr;
    
    initialized_.store(false);
    LOGI("ClipboardHook cleanup completed");
    return SUCCESS;
}

/**
 * Waits for the monitoring thread to stop gracefully.
 * Requirements: 7.5
 */
void ClipboardHook::waitForMonitoringThreadStop() {
    if (g_monitoringThreadRunning.load()) {
        LOGD("Waiting for monitoring thread to stop...");
        
        // Signal the thread to stop
        {
            std::lock_guard<std::mutex> lock(g_monitoringMutex);
            g_monitoringCV.notify_all();
        }
        
        // Wait with timeout
        auto startTime = std::chrono::steady_clock::now();
        while (g_monitoringThreadRunning.load()) {
            auto elapsed = std::chrono::steady_clock::now() - startTime;
            if (elapsed > std::chrono::seconds(5)) {
                LOGW("Monitoring thread did not stop within timeout");
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        
        // Join the thread if joinable
        if (g_monitoringThread.joinable()) {
            g_monitoringThread.join();
        }
    }
}

bool ClipboardHook::checkRootAccess() {
    // Check if su binary exists and is executable
    const char* suPaths[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su"
    };
    
    bool suFound = false;
    for (const char* path : suPaths) {
        if (access(path, X_OK) == 0) {
            LOGD("Found su binary at: %s", path);
            suFound = true;
            break;
        }
    }
    
    if (!suFound) {
        LOGD("No su binary found");
        return false;
    }
    
    // Try to execute a simple su command
    int result = executeRootCommand("su -c 'echo test'");
    return result == 0;
}

int ClipboardHook::executeRootCommand(const std::string& command) {
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) {
        LOGE("Failed to execute root command: %s", command.c_str());
        return -1;
    }
    
    // Read output to prevent blocking
    char buffer[256];
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        // Discard output
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
    
    // Stop any existing monitoring thread
    waitForMonitoringThreadStop();
    
    // Start new monitoring thread
    g_monitoringThreadRunning.store(true);
    
    g_monitoringThread = std::thread([this]() {
        LOGD("Monitoring thread started");
        
        std::string lastContent;
        int consecutiveErrors = 0;
        const int maxConsecutiveErrors = 5;
        
        while (monitoring_.load() && g_monitoringThreadRunning.load()) {
            try {
                std::string currentContent = getCurrentClipboardContent();
                
                if (!currentContent.empty() && currentContent != lastContent) {
                    lastContent = currentContent;
                    int64_t timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::system_clock::now().time_since_epoch()).count();
                    
                    notifyClipboardChange(currentContent, timestamp);
                    consecutiveErrors = 0; // Reset error count on success
                }
                
                // Wait with condition variable for graceful shutdown
                {
                    std::unique_lock<std::mutex> lock(g_monitoringMutex);
                    g_monitoringCV.wait_for(lock, std::chrono::milliseconds(500), [this]() {
                        return !monitoring_.load() || !g_monitoringThreadRunning.load();
                    });
                }
                
            } catch (const std::exception& e) {
                LOGE("Exception in monitoring thread: %s", e.what());
                consecutiveErrors++;
                
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    LOGE("Too many consecutive errors, stopping monitoring");
                    notifyError(ERROR_SYSTEM_CALL_FAILED, "Too many consecutive errors in monitoring");
                    break;
                }
                
                // Back off on errors
                std::this_thread::sleep_for(std::chrono::milliseconds(1000 * consecutiveErrors));
            }
        }
        
        g_monitoringThreadRunning.store(false);
        LOGD("Monitoring thread stopped");
    });
    
    LOGD("System-level monitoring started");
    return SUCCESS;
}

int ClipboardHook::stopSystemLevelMonitoring() {
    LOGD("Stopping system-level monitoring");
    
    // Signal the monitoring thread to stop
    g_monitoringThreadRunning.store(false);
    
    {
        std::lock_guard<std::mutex> lock(g_monitoringMutex);
        g_monitoringCV.notify_all();
    }
    
    LOGD("System-level monitoring stop signaled");
    return SUCCESS;
}

/**
 * Notifies Java layer of clipboard change.
 * Requirements: 7.3
 */
void ClipboardHook::notifyClipboardChange(const std::string& content, int64_t timestamp) {
    if (!javaCallbackObject_ || !jvm_) {
        LOGW("Java callback not available");
        return;
    }
    
    JNIEnv* env = nullptr;
    bool needsDetach = false;
    
    // Get JNI environment, attaching thread if necessary
    jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if (jvm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach thread to JVM");
            return;
        }
        needsDetach = true;
    } else if (result != JNI_OK) {
        LOGE("Failed to get JNI environment: %d", result);
        return;
    }
    
    // Create Java string
    jstring jContent = env->NewStringUTF(content.c_str());
    if (!jContent) {
        LOGE("Failed to create Java string for clipboard content");
        if (needsDetach) {
            jvm_->DetachCurrentThread();
        }
        return;
    }
    
    // Call Java callback
    env->CallVoidMethod(javaCallbackObject_, onClipboardChangedMethod_, jContent, timestamp);
    
    // Check for exceptions
    if (env->ExceptionCheck()) {
        LOGE("Exception occurred in Java clipboard callback");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    
    // Clean up local reference
    env->DeleteLocalRef(jContent);
    
    // Detach thread if we attached it
    if (needsDetach) {
        jvm_->DetachCurrentThread();
    }
}

/**
 * Notifies Java layer of native error.
 * Requirements: 7.3
 */
void ClipboardHook::notifyError(int errorCode, const std::string& message) {
    if (!javaCallbackObject_ || !jvm_) {
        LOGW("Java callback not available for error notification");
        return;
    }
    
    JNIEnv* env = nullptr;
    bool needsDetach = false;
    
    // Get JNI environment, attaching thread if necessary
    jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if (jvm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach thread to JVM for error notification");
            return;
        }
        needsDetach = true;
    } else if (result != JNI_OK) {
        LOGE("Failed to get JNI environment for error notification: %d", result);
        return;
    }
    
    // Create Java string
    jstring jMessage = env->NewStringUTF(message.c_str());
    if (!jMessage) {
        LOGE("Failed to create Java string for error message");
        if (needsDetach) {
            jvm_->DetachCurrentThread();
        }
        return;
    }
    
    // Call Java callback
    env->CallVoidMethod(javaCallbackObject_, onNativeErrorMethod_, errorCode, jMessage);
    
    // Check for exceptions
    if (env->ExceptionCheck()) {
        LOGE("Exception occurred in Java error callback");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    
    // Clean up local reference
    env->DeleteLocalRef(jMessage);
    
    // Detach thread if we attached it
    if (needsDetach) {
        jvm_->DetachCurrentThread();
    }
}

/**
 * Gets monitoring statistics.
 */
bool ClipboardHook::isMonitoring() const {
    return monitoring_.load();
}

/**
 * Gets initialization status.
 */
bool ClipboardHook::isInitialized() const {
    return initialized_.load();
}

// C-style callbacks for system hooks
extern "C" {
    void clipboard_change_callback(const char* content, int64_t timestamp) {
        if (content) {
            ClipboardHook& hook = ClipboardHook::getInstance();
            hook.notifyClipboardChange(std::string(content), timestamp);
        }
    }
    
    void clipboard_error_callback(int error_code, const char* message) {
        ClipboardHook& hook = ClipboardHook::getInstance();
        hook.notifyError(error_code, message ? std::string(message) : "Unknown error");
    }
}