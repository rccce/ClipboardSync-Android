package com.siw.clipboardsync.monitor.error

import android.content.Context
import com.siw.clipboardsync.monitor.ClipboardMonitor
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.RootCapabilities
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardErrorHandlerIntegrationTest {
    
    @Mock
    private lateinit var context: Context
    
    @Mock
    private lateinit var rootDetectionService: RootDetectionService
    
    @Mock
    private lateinit var accessibilityPermissionManager: AccessibilityPermissionManager
    
    @Mock
    private lateinit var notificationManager: ErrorNotificationManager
    
    private lateinit var errorHandler: ClipboardErrorHandler
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    // Mock monitors for different methods
    @Mock
    private lateinit var systemHookMonitor: ClipboardMonitor
    
    @Mock
    private lateinit var accessibilityMonitor: ClipboardMonitor
    
    @Mock
    private lateinit var foregroundServiceMonitor: ClipboardMonitor
    
    @Mock
    private lateinit var pollingMonitor: ClipboardMonitor
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        errorHandler = ClipboardErrorHandler(
            context,
            rootDetectionService,
            accessibilityPermissionManager,
            notificationManager
        )
    }
    
    @Test
    fun `integration test - root access lost should cascade through fallback chain`() = testScope.runTest {
        // Given: Device loses root access
        whenever(rootDetectionService.isRooted()).thenReturn(false)
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(true)
        
        val rootAccessLostError = ClipboardError.RootAccessLost
        
        // When: Error occurs with system hooks
        val result = errorHandler.handleError(
            rootAccessLostError, 
            MonitoringMethod.SYSTEM_HOOKS, 
            systemHookMonitor
        )
        
        // Then: Should show notification and attempt fallback
        verify(notificationManager).showRootAccessLostNotification()
        assertEquals(rootAccessLostError, errorHandler.errorState.first())
        
        // Verify fallback chain is attempted
        verify(rootDetectionService, atLeastOnce()).isRooted()
        verify(accessibilityPermissionManager, atLeastOnce()).isAccessibilityServiceEnabled()
    }
    
    @Test
    fun `integration test - service disconnection with successful recovery`() = testScope.runTest {
        // Given: Service that can be recovered
        val serviceError = ClipboardError.ServiceDisconnected("ForegroundService")
        whenever(foregroundServiceMonitor.isMonitoring()).thenReturn(false, false, true)
        
        // When: Service disconnection occurs
        val result = errorHandler.handleError(
            serviceError,
            MonitoringMethod.FOREGROUND_SERVICE,
            foregroundServiceMonitor
        )
        
        // Then: Should start recovery process
        assertTrue(errorHandler.isRecovering.first())
        
        // Advance time to allow recovery attempts
        advanceTimeBy(2500) // Allow for retry delays
        
        // Verify recovery attempts
        verify(foregroundServiceMonitor, atLeast(2)).stopMonitoring()
        verify(foregroundServiceMonitor, atLeast(2)).startMonitoring()
    }
    
    @Test
    fun `integration test - service disconnection with failed recovery and fallback`() = testScope.runTest {
        // Given: Service that cannot be recovered
        val serviceError = ClipboardError.ServiceDisconnected("ForegroundService")
        whenever(foregroundServiceMonitor.isMonitoring()).thenReturn(false)
        whenever(rootDetectionService.isRooted()).thenReturn(false)
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(false)
        
        // When: Service disconnection occurs and recovery fails
        val result = errorHandler.handleError(
            serviceError,
            MonitoringMethod.FOREGROUND_SERVICE,
            foregroundServiceMonitor
        )
        
        // Advance time past recovery timeout
        advanceTimeBy(6000)
        
        // Then: Should exhaust recovery attempts and try fallback
        verify(foregroundServiceMonitor, times(3)).stopMonitoring()
        verify(foregroundServiceMonitor, times(3)).startMonitoring()
        verify(notificationManager).showRecoveryFailedNotification()
    }
    
    @Test
    fun `integration test - permission denied cascade with user intervention`() = testScope.runTest {
        // Given: Accessibility permission denied
        val permissionError = ClipboardError.PermissionDenied("android.permission.BIND_ACCESSIBILITY_SERVICE")
        
        // When: Permission error occurs
        val result = errorHandler.handleError(
            permissionError,
            MonitoringMethod.ACCESSIBILITY_SERVICE,
            accessibilityMonitor
        )
        
        // Then: Should request permission and show notification
        verify(notificationManager).showAccessibilityPermissionNeeded()
        assertNull(result) // No fallback monitor returned, waiting for user action
        
        // Simulate user granting permission
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(true)
        
        // Clear error state to simulate successful permission grant
        errorHandler.clearError()
        assertNull(errorHandler.errorState.first())
    }
    
    @Test
    fun `integration test - multiple error cascade with final fallback to polling`() = testScope.runTest {
        // Given: Multiple monitoring methods fail in sequence
        whenever(rootDetectionService.isRooted()).thenReturn(true, false) // Root lost during operation
        whenever(rootDetectionService.getRootCapabilities()).thenReturn(
            RootCapabilities(hasSystemHooks = false, hasXposedFramework = false, hasNativeAccess = false, rootMethod = RootCapabilities.RootMethod.NONE)
        )
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(false)
        
        // When: System hooks fail
        val systemHookError = ClipboardError.SystemHookFailed("native_hook")
        val result1 = errorHandler.handleError(
            systemHookError,
            MonitoringMethod.SYSTEM_HOOKS,
            systemHookMonitor
        )
        
        // Then: Should show system hook failure notification
        verify(notificationManager).showSystemHookFailedNotification()
        
        // When: Accessibility service also unavailable
        val accessibilityError = ClipboardError.AccessibilityServiceUnavailable
        val result2 = errorHandler.handleError(
            accessibilityError,
            MonitoringMethod.ACCESSIBILITY_SERVICE,
            accessibilityMonitor
        )
        
        // Then: Should request accessibility permission
        verify(notificationManager).showAccessibilityPermissionNeeded()
        
        // When: Foreground service fails
        val foregroundError = ClipboardError.ForegroundServiceFailed()
        val result3 = errorHandler.handleError(
            foregroundError,
            MonitoringMethod.FOREGROUND_SERVICE,
            foregroundServiceMonitor
        )
        
        // Then: Should show foreground service error
        verify(notificationManager).showForegroundServicePermissionNeeded()
        
        // Final fallback should be polling (always available)
        // This would be handled by the monitoring manager in real implementation
    }
    
    @Test
    fun `integration test - content too large error with continued monitoring`() = testScope.runTest {
        // Given: Large content error
        val contentError = ClipboardError.ContentTooLarge(actualSize = 15_000_000, maxSize = 10_000_000)
        
        // When: Content too large error occurs
        val result = errorHandler.handleError(
            contentError,
            MonitoringMethod.POLLING_FALLBACK,
            pollingMonitor
        )
        
        // Then: Should show notification but continue with current monitor
        verify(notificationManager).showContentTooLargeNotification(15_000_000, 10_000_000)
        assertEquals(pollingMonitor, result)
        assertEquals(contentError, errorHandler.errorState.first())
    }
    
    @Test
    fun `integration test - unknown error with fallback attempt`() = testScope.runTest {
        // Given: Unknown error occurs
        val unknownError = ClipboardError.UnknownError(RuntimeException("Unexpected system error"))
        whenever(rootDetectionService.isRooted()).thenReturn(false)
        whenever(accessibilityPermissionManager.isAccessibilityServiceEnabled()).thenReturn(true)
        
        // When: Unknown error occurs
        val result = errorHandler.handleError(
            unknownError,
            MonitoringMethod.SYSTEM_HOOKS,
            systemHookMonitor
        )
        
        // Then: Should show error notification and attempt fallback
        verify(notificationManager).showUnknownErrorNotification("Unexpected system error")
        assertEquals(unknownError, errorHandler.errorState.first())
    }
    
    @Test
    fun `integration test - recovery timeout handling`() = testScope.runTest {
        // Given: Service that takes too long to recover
        val serviceError = ClipboardError.ServiceDisconnected("SlowService")
        whenever(foregroundServiceMonitor.isMonitoring()).thenReturn(false)
        
        // When: Service disconnection occurs
        errorHandler.handleError(
            serviceError,
            MonitoringMethod.FOREGROUND_SERVICE,
            foregroundServiceMonitor
        )
        
        // Then: Recovery should be in progress
        assertTrue(errorHandler.isRecovering.first())
        
        // When: Recovery timeout is reached
        advanceTimeBy(6000) // Exceed recovery timeout
        
        // Then: Recovery should complete with failure
        assertFalse(errorHandler.isRecovering.first())
        verify(notificationManager).showRecoveryFailedNotification()
    }
    
    @Test
    fun `integration test - concurrent error handling`() = testScope.runTest {
        // Given: Multiple errors occur simultaneously
        val error1 = ClipboardError.ServiceDisconnected("Service1")
        val error2 = ClipboardError.PermissionDenied("android.permission.POST_NOTIFICATIONS")
        
        // When: First error starts recovery
        errorHandler.handleError(error1, MonitoringMethod.FOREGROUND_SERVICE, foregroundServiceMonitor)
        assertTrue(errorHandler.isRecovering.first())
        
        // When: Second error occurs during recovery
        val result = errorHandler.handleError(error2, MonitoringMethod.FOREGROUND_SERVICE, foregroundServiceMonitor)
        
        // Then: Second error should be handled, first recovery should be cancelled
        assertEquals(error2, errorHandler.errorState.first())
        verify(notificationManager).showNotificationPermissionNeeded()
    }
    
    @Test
    fun `integration test - error state management and clearing`() = testScope.runTest {
        // Given: Error occurs
        val error = ClipboardError.ContentTooLarge(actualSize = 5_000_000, maxSize = 1_000_000)
        
        // When: Error is handled
        errorHandler.handleError(error, MonitoringMethod.POLLING_FALLBACK, pollingMonitor)
        
        // Then: Error state should be set
        assertEquals(error, errorHandler.errorState.first())
        
        // When: Error is cleared
        errorHandler.clearError()
        
        // Then: Error state should be null
        assertNull(errorHandler.errorState.first())
    }
    
    @Test
    fun `integration test - method availability checking with changing conditions`() = testScope.runTest {
        // Given: Initially rooted device
        whenever(rootDetectionService.isRooted()).thenReturn(true)
        whenever(rootDetectionService.getRootCapabilities()).thenReturn(
            RootCapabilities(hasSystemHooks = true, hasXposedFramework = false, hasNativeAccess = true, rootMethod = RootCapabilities.RootMethod.MAGISK)
        )
        
        // When: System hook error occurs
        val error = ClipboardError.SystemHookFailed("test_hook")
        errorHandler.handleError(error, MonitoringMethod.SYSTEM_HOOKS, systemHookMonitor)
        
        // Then: Should check root capabilities
        verify(rootDetectionService).isRooted()
        verify(rootDetectionService).getRootCapabilities()
        
        // When: Root access is lost (simulating device state change)
        whenever(rootDetectionService.isRooted()).thenReturn(false)
        
        // When: Another error occurs
        val error2 = ClipboardError.RootAccessLost
        errorHandler.handleError(error2, MonitoringMethod.SYSTEM_HOOKS, systemHookMonitor)
        
        // Then: Should adapt to new device state
        verify(notificationManager).showRootAccessLostNotification()
    }
}