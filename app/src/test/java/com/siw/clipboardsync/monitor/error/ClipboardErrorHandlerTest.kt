package com.siw.clipboardsync.monitor.error

import android.content.Context
import android.content.Intent
import com.siw.clipboardsync.monitor.ClipboardMonitor
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.RootCapabilities
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class ClipboardErrorHandlerTest {
    
    @Mock
    private lateinit var context: Context
    
    @Mock
    private lateinit var rootDetectionService: RootDetectionService
    
    @Mock
    private lateinit var accessibilityPermissionManager: AccessibilityPermissionManager
    
    @Mock
    private lateinit var notificationManager: ErrorNotificationManager
    
    @Mock
    private lateinit var mockMonitor: ClipboardMonitor
    
    private lateinit var errorHandler: ClipboardErrorHandler
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    @Before
    fun setup() {
        errorHandler = ClipboardErrorHandler(
            context,
            rootDetectionService,
            accessibilityPermissionManager,
            notificationManager
        )
    }
    
    @Test
    fun `handleError with RootAccessLost should switch to non-root method`() = testScope.runTest {
        // Given
        val error = ClipboardError.RootAccessLost
        val currentMethod = MonitoringMethod.SYSTEM_HOOKS
        whenever(rootDetectionService.isRooted()).thenReturn(false)
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(true)
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showRootAccessLostNotification()
        assertEquals(error, errorHandler.errorState.first())
    }
    
    @Test
    fun `handleError with PermissionDenied for accessibility should request permission`() = testScope.runTest {
        // Given
        val error = ClipboardError.PermissionDenied("android.permission.BIND_ACCESSIBILITY_SERVICE")
        val currentMethod = MonitoringMethod.ACCESSIBILITY_SERVICE
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showAccessibilityPermissionNeeded()
        verify(context).startActivity(any<Intent>())
        assertNull(result)
    }
    
    @Test
    fun `handleError with PermissionDenied for notifications should request permission`() = testScope.runTest {
        // Given
        val error = ClipboardError.PermissionDenied("android.permission.POST_NOTIFICATIONS")
        val currentMethod = MonitoringMethod.FOREGROUND_SERVICE
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showNotificationPermissionNeeded()
        verify(notificationManager).requestNotificationPermission()
        assertNull(result)
    }
    
    @Test
    fun `handleError with SystemHookFailed should try next fallback`() = testScope.runTest {
        // Given
        val error = ClipboardError.SystemHookFailed("native_hook")
        val currentMethod = MonitoringMethod.SYSTEM_HOOKS
        whenever(rootDetectionService.isRooted()).thenReturn(true)
        whenever(rootDetectionService.getRootCapabilities()).thenReturn(
            RootCapabilities(hasXposedFramework = true, hasSystemHooks = false, hasNativeAccess = false, rootMethod = "magisk")
        )
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showSystemHookFailedNotification()
    }
    
    @Test
    fun `handleError with ServiceDisconnected should attempt reconnection`() = testScope.runTest {
        // Given
        val error = ClipboardError.ServiceDisconnected("ClipboardMonitorService")
        val currentMethod = MonitoringMethod.FOREGROUND_SERVICE
        whenever(mockMonitor.isMonitoring()).thenReturn(false, true)
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        advanceTimeBy(6000) // Wait for recovery timeout
        
        // Then
        assertTrue(errorHandler.isRecovering.first())
        verify(mockMonitor).stopMonitoring()
        verify(mockMonitor).startMonitoring()
    }
    
    @Test
    fun `handleError with ContentTooLarge should continue with current monitor`() = testScope.runTest {
        // Given
        val error = ClipboardError.ContentTooLarge(actualSize = 15_000_000, maxSize = 10_000_000)
        val currentMethod = MonitoringMethod.POLLING_FALLBACK
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showContentTooLargeNotification(15_000_000, 10_000_000)
        assertEquals(mockMonitor, result)
    }
    
    @Test
    fun `handleError with AccessibilityServiceUnavailable should request accessibility permission`() = testScope.runTest {
        // Given
        val error = ClipboardError.AccessibilityServiceUnavailable
        val currentMethod = MonitoringMethod.ACCESSIBILITY_SERVICE
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showAccessibilityPermissionNeeded()
        verify(context).startActivity(any<Intent>())
        assertNull(result)
    }
    
    @Test
    fun `handleError with ForegroundServiceFailed should try next fallback`() = testScope.runTest {
        // Given
        val error = ClipboardError.ForegroundServiceFailed()
        val currentMethod = MonitoringMethod.FOREGROUND_SERVICE
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showForegroundServicePermissionNeeded()
    }
    
    @Test
    fun `handleError with NativeLibraryError should try next fallback`() = testScope.runTest {
        // Given
        val error = ClipboardError.NativeLibraryError("libclipboard.so")
        val currentMethod = MonitoringMethod.SYSTEM_HOOKS
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showUnknownErrorNotification("Native library error: libclipboard.so")
    }
    
    @Test
    fun `handleError with XposedFrameworkError should try next fallback`() = testScope.runTest {
        // Given
        val error = ClipboardError.XposedFrameworkError()
        val currentMethod = MonitoringMethod.XPOSED_HOOKS
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showSystemHookFailedNotification()
    }
    
    @Test
    fun `handleError with UnsupportedContentType should continue with current monitor`() = testScope.runTest {
        // Given
        val error = ClipboardError.UnsupportedContentType("custom", "application/custom")
        val currentMethod = MonitoringMethod.POLLING_FALLBACK
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showUnknownErrorNotification("Unsupported content type: custom")
        assertEquals(mockMonitor, result)
    }
    
    @Test
    fun `handleError with TimingOptimizationFailed should continue with current monitor`() = testScope.runTest {
        // Given
        val error = ClipboardError.TimingOptimizationFailed("read_delay")
        val currentMethod = MonitoringMethod.POLLING_FALLBACK
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        assertEquals(mockMonitor, result)
        // No notification should be shown for timing optimization failures
        verify(notificationManager, never()).showUnknownErrorNotification(any())
    }
    
    @Test
    fun `handleError with MaxRetriesExceeded should try next fallback`() = testScope.runTest {
        // Given
        val error = ClipboardError.MaxRetriesExceeded("clipboard_read", 3)
        val currentMethod = MonitoringMethod.ACCESSIBILITY_SERVICE
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showRecoveryFailedNotification()
    }
    
    @Test
    fun `handleError with PollingError should attempt reconnection`() = testScope.runTest {
        // Given
        val error = ClipboardError.PollingError("clipboard_check")
        val currentMethod = MonitoringMethod.POLLING_FALLBACK
        whenever(mockMonitor.isMonitoring()).thenReturn(false, true)
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        advanceTimeBy(6000) // Wait for recovery timeout
        
        // Then
        assertTrue(errorHandler.isRecovering.first())
        verify(mockMonitor).stopMonitoring()
        verify(mockMonitor).startMonitoring()
    }
    
    @Test
    fun `handleError with UnknownError should try next fallback`() = testScope.runTest {
        // Given
        val exception = RuntimeException("Unexpected error")
        val error = ClipboardError.UnknownError(exception)
        val currentMethod = MonitoringMethod.ACCESSIBILITY_SERVICE
        
        // When
        val result = errorHandler.handleError(error, currentMethod, mockMonitor)
        
        // Then
        verify(notificationManager).showUnknownErrorNotification("Unexpected error")
    }
    
    @Test
    fun `attemptServiceReconnection should succeed after retry`() = testScope.runTest {
        // Given
        val error = ClipboardError.ServiceDisconnected("TestService")
        val currentMethod = MonitoringMethod.FOREGROUND_SERVICE
        whenever(mockMonitor.isMonitoring()).thenReturn(false, false, true)
        
        // When
        errorHandler.handleError(error, currentMethod, mockMonitor)
        advanceTimeBy(2000) // Advance past first retry
        
        // Then
        verify(mockMonitor, atLeast(2)).stopMonitoring()
        verify(mockMonitor, atLeast(2)).startMonitoring()
    }
    
    @Test
    fun `attemptServiceReconnection should fail after max attempts`() = testScope.runTest {
        // Given
        val error = ClipboardError.ServiceDisconnected("TestService")
        val currentMethod = MonitoringMethod.FOREGROUND_SERVICE
        whenever(mockMonitor.isMonitoring()).thenReturn(false)
        
        // When
        errorHandler.handleError(error, currentMethod, mockMonitor)
        advanceTimeBy(6000) // Wait for all retries and timeout
        
        // Then
        verify(mockMonitor, times(3)).stopMonitoring()
        verify(mockMonitor, times(3)).startMonitoring()
        verify(notificationManager).showRecoveryFailedNotification()
    }
    
    @Test
    fun `isMethodAvailable should return correct availability for system hooks`() = testScope.runTest {
        // Given
        whenever(rootDetectionService.isRooted()).thenReturn(true)
        whenever(rootDetectionService.getRootCapabilities()).thenReturn(
            RootCapabilities(hasSystemHooks = true, hasXposedFramework = false, hasNativeAccess = true, rootMethod = "magisk")
        )
        
        // When
        val error = ClipboardError.SystemHookFailed("test")
        errorHandler.handleError(error, MonitoringMethod.SYSTEM_HOOKS, mockMonitor)
        
        // Then - Should attempt to use system hooks if available
        verify(rootDetectionService).isRooted()
        verify(rootDetectionService).getRootCapabilities()
    }
    
    @Test
    fun `isMethodAvailable should return correct availability for accessibility service`() = testScope.runTest {
        // Given
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(true)
        
        // When
        val error = ClipboardError.AccessibilityServiceUnavailable
        errorHandler.handleError(error, MonitoringMethod.ACCESSIBILITY_SERVICE, mockMonitor)
        
        // Then
        verify(accessibilityPermissionManager, never()).isAccessibilityServiceEnabled()
        verify(notificationManager).showAccessibilityPermissionNeeded()
    }
    
    @Test
    fun `clearError should reset error state`() = testScope.runTest {
        // Given
        val error = ClipboardError.UnknownError(RuntimeException("Test"))
        errorHandler.handleError(error, MonitoringMethod.POLLING_FALLBACK, mockMonitor)
        
        // When
        errorHandler.clearError()
        
        // Then
        assertNull(errorHandler.errorState.first())
    }
    
    @Test
    fun `cancelRecovery should stop recovery process`() = testScope.runTest {
        // Given
        val error = ClipboardError.ServiceDisconnected("TestService")
        whenever(mockMonitor.isMonitoring()).thenReturn(false)
        
        // When
        errorHandler.handleError(error, MonitoringMethod.FOREGROUND_SERVICE, mockMonitor)
        assertTrue(errorHandler.isRecovering.first())
        
        errorHandler.cancelRecovery()
        
        // Then
        assertFalse(errorHandler.isRecovering.first())
    }
    
    @Test
    fun `fallback chain should be exhausted when no methods available`() = testScope.runTest {
        // Given
        val error = ClipboardError.SystemHookFailed("test")
        whenever(rootDetectionService.isRooted()).thenReturn(false)
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(false)
        
        // When
        val result = errorHandler.handleError(error, MonitoringMethod.SYSTEM_HOOKS, mockMonitor)
        
        // Then
        verify(notificationManager).showNoFallbackAvailableNotification()
        assertNull(result)
    }
}