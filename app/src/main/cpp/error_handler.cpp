#include "clipboard_hook.h"
#include <android/log.h>
#include <string>
#include <map>
#include <mutex>

#define LOG_TAG "ErrorHandler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/**
 * Native error handling and propagation to Java layer
 */

namespace ErrorHandler {

// Error code to message mapping
static std::map<int, std::string> errorMessages = {
    {ClipboardHook::SUCCESS, "Operation completed successfully"},
    {ClipboardHook::ERROR_INITIALIZATION_FAILED, "Failed to initialize clipboard hooks"},
    {ClipboardHook::ERROR_HOOK_REGISTRATION_FAILED, "Failed to register system hooks"},
    {ClipboardHook::ERROR_MONITORING_START_FAILED, "Failed to start clipboard monitoring"},
    {ClipboardHook::ERROR_PERMISSION_DENIED, "Permission denied - root access required"},
    {ClipboardHook::ERROR_SYSTEM_CALL_FAILED, "System call failed"},
    {ClipboardHook::ERROR_INVALID_STATE, "Invalid state - operation not allowed"}
};

static std::mutex errorMutex;
static int lastErrorCode = ClipboardHook::SUCCESS;
static std::string lastErrorMessage;

/**
 * Get human-readable error message for error code
 */
std::string getErrorMessage(int errorCode) {
    std::lock_guard<std::mutex> lock(errorMutex);
    
    auto it = errorMessages.find(errorCode);
    if (it != errorMessages.end()) {
        return it->second;
    }
    
    return "Unknown error code: " + std::to_string(errorCode);
}

/**
 * Set last error information
 */
void setLastError(int errorCode, const std::string& additionalInfo = "") {
    std::lock_guard<std::mutex> lock(errorMutex);
    
    lastErrorCode = errorCode;
    lastErrorMessage = getErrorMessage(errorCode);
    
    if (!additionalInfo.empty()) {
        lastErrorMessage += " - " + additionalInfo;
    }
    
    LOGE("Error set: %d - %s", errorCode, lastErrorMessage.c_str());
}

/**
 * Get last error code
 */
int getLastErrorCode() {
    std::lock_guard<std::mutex> lock(errorMutex);
    return lastErrorCode;
}

/**
 * Get last error message
 */
std::string getLastErrorMessage() {
    std::lock_guard<std::mutex> lock(errorMutex);
    return lastErrorMessage;
}

/**
 * Clear last error
 */
void clearLastError() {
    std::lock_guard<std::mutex> lock(errorMutex);
    lastErrorCode = ClipboardHook::SUCCESS;
    lastErrorMessage.clear();
}

/**
 * Check if operation should be retried based on error code
 */
bool shouldRetryOperation(int errorCode) {
    switch (errorCode) {
        case ClipboardHook::ERROR_SYSTEM_CALL_FAILED:
        case ClipboardHook::ERROR_MONITORING_START_FAILED:
            return true;
        
        case ClipboardHook::ERROR_PERMISSION_DENIED:
        case ClipboardHook::ERROR_INITIALIZATION_FAILED:
        case ClipboardHook::ERROR_INVALID_STATE:
            return false;
        
        default:
            return false;
    }
}

/**
 * Get suggested recovery action for error code
 */
std::string getRecoveryAction(int errorCode) {
    switch (errorCode) {
        case ClipboardHook::ERROR_PERMISSION_DENIED:
            return "Ensure device is rooted and app has root permissions";
        
        case ClipboardHook::ERROR_INITIALIZATION_FAILED:
            return "Restart the application and check system compatibility";
        
        case ClipboardHook::ERROR_HOOK_REGISTRATION_FAILED:
            return "Try alternative monitoring methods or restart the service";
        
        case ClipboardHook::ERROR_MONITORING_START_FAILED:
            return "Check system resources and try again";
        
        case ClipboardHook::ERROR_SYSTEM_CALL_FAILED:
            return "Verify root access and system permissions";
        
        case ClipboardHook::ERROR_INVALID_STATE:
            return "Initialize the clipboard hook before performing operations";
        
        default:
            return "Contact support with error details";
    }
}

/**
 * Log detailed error information
 */
void logDetailedError(int errorCode, const char* function, const char* file, int line) {
    std::string message = getErrorMessage(errorCode);
    std::string recovery = getRecoveryAction(errorCode);
    
    LOGE("Detailed Error Report:");
    LOGE("  Code: %d", errorCode);
    LOGE("  Message: %s", message.c_str());
    LOGE("  Function: %s", function);
    LOGE("  File: %s:%d", file, line);
    LOGE("  Recovery: %s", recovery.c_str());
    LOGE("  Retry: %s", shouldRetryOperation(errorCode) ? "Yes" : "No");
}

/**
 * Handle critical errors that require immediate attention
 */
void handleCriticalError(int errorCode, const std::string& context) {
    LOGE("CRITICAL ERROR in %s: %d - %s", 
         context.c_str(), errorCode, getErrorMessage(errorCode).c_str());
    
    // For critical errors, we might want to:
    // 1. Stop all monitoring operations
    // 2. Clean up resources
    // 3. Notify the Java layer immediately
    
    setLastError(errorCode, "Critical error in " + context);
}

/**
 * Validate error code range
 */
bool isValidErrorCode(int errorCode) {
    return errorCode >= ClipboardHook::ERROR_INVALID_STATE && 
           errorCode <= ClipboardHook::SUCCESS;
}

} // namespace ErrorHandler

// Convenience macros for error handling
#define SET_ERROR(code, info) ErrorHandler::setLastError(code, info)
#define LOG_ERROR(code, func, file, line) ErrorHandler::logDetailedError(code, func, file, line)
#define HANDLE_CRITICAL(code, context) ErrorHandler::handleCriticalError(code, context)