#ifndef CLIPBOARD_HOOK_H
#define CLIPBOARD_HOOK_H

#include <jni.h>
#include <string>
#include <functional>
#include <memory>
#include <atomic>
#include <mutex>

/**
 * Native clipboard hook implementation for Android system-level clipboard monitoring
 */
class ClipboardHook {
public:
    // Error codes
    static constexpr int SUCCESS = 0;
    static constexpr int ERROR_INITIALIZATION_FAILED = -1;
    static constexpr int ERROR_HOOK_REGISTRATION_FAILED = -2;
    static constexpr int ERROR_MONITORING_START_FAILED = -3;
    static constexpr int ERROR_PERMISSION_DENIED = -4;
    static constexpr int ERROR_SYSTEM_CALL_FAILED = -5;
    static constexpr int ERROR_INVALID_STATE = -6;

    // Callback types
    using ClipboardChangeCallback = std::function<void(const std::string&, int64_t)>;
    using ErrorCallback = std::function<void(int, const std::string&)>;

    ClipboardHook();
    ~ClipboardHook();

    // Core functionality
    int initialize();
    int registerCallback(JNIEnv* env, jobject javaObject);
    int startMonitoring();
    int stopMonitoring();
    int cleanup();

    // Clipboard operations
    std::string getCurrentClipboardContent();
    int setClipboardContent(const std::string& content);

    // State queries
    bool isInitialized() const { return initialized_.load(); }
    bool isMonitoring() const { return monitoring_.load(); }

    // Static instance access
    static ClipboardHook& getInstance();

private:
    // Internal state
    std::atomic<bool> initialized_{false};
    std::atomic<bool> monitoring_{false};
    
    // JNI references
    JavaVM* jvm_{nullptr};
    jobject javaCallbackObject_{nullptr};
    jmethodID onClipboardChangedMethod_{nullptr};
    jmethodID onNativeErrorMethod_{nullptr};

    // System hooks
    void* systemHookHandle_{nullptr};
    
public:
    // Public methods for C-style callbacks
    void notifyClipboardChange(const std::string& content, int64_t timestamp);
    void notifyError(int errorCode, const std::string& message);

private:
    // Internal methods
    int setupSystemHooks();
    int teardownSystemHooks();
    
    // System-level clipboard monitoring
    int startSystemLevelMonitoring();
    int stopSystemLevelMonitoring();
    
    // Root access utilities
    bool checkRootAccess();
    int executeRootCommand(const std::string& command);
    
    // Thread safety
    mutable std::mutex hookMutex_;
    
    // Prevent copy/assignment
    ClipboardHook(const ClipboardHook&) = delete;
    ClipboardHook& operator=(const ClipboardHook&) = delete;
};

// C-style callback for system hooks
extern "C" {
    void clipboard_change_callback(const char* content, int64_t timestamp);
    void clipboard_error_callback(int error_code, const char* message);
}

#endif // CLIPBOARD_HOOK_H