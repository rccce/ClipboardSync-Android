package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.Build
import com.siw.clipboardsync.monitor.error.ClipboardErrorHandler
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.RootCapabilities
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for ClipboardMonitorManager with real MonitoringStrategyFactory.
 * Tests the coordination between manager and strategy selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R]) // Android 11
class ClipboardMonitorManagerIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var rootDetectionService: RootDetectionService
    private lateinit var strategyFactory: MonitoringStrategyFactory
    private lateinit var errorHandler: ClipboardErrorHandler
    private lateinit var systemLevelMonitor: SystemLevelClipboardMonitor
    private lateinit var accessibilityMonitor: AccessibilityClipboardMonitor
    private lateinit var foregroundServiceMonitor: ForegroundServiceClipboardMonitor
    private lateinit var pollingMonitor: PollingClipboardMonitor
    private lateinit var monitorManager: ClipboardMonitorManager
    private lateinit var testListener: TestClipboardListener
    
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        rootDetectionService = mockk()
        errorHandler = mockk(relaxed = true)
        systemLevelMonitor = mockk(relaxed = true)
        accessibilityMonitor = mockk(relaxed = true)
        foregroundServiceMonitor = mockk(relaxed = true)
        pollingMonitor = mockk(relaxed = true)
        testListener = TestClipboardListener()
        
        // Mock AccessibilityPermissionManager constructor
        mockkConstructor(AccessibilityPermissionManager::class)
        
        // Create real strategy factory
        strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        
        monitorManager = ClipboardMonitorManager(
            context = context,
            strategyFactory = strategyFactory,
            errorHandler = errorHandler,
            systemLevelMonitor = systemLevelMonitor,
            accessibilityMonitor = accessibilityMonitor,
            foregroundServiceMonitor = foregroundServiceMonitor,
            pollingMonitor = pollingMonitor
        )
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `rooted device selects system hooks as optimal strategy`() = runTest {
        // Given: Rooted device with system hooks capability
        setupRootedDevice(hasSystemHooks = true, hasXposed = false)
        setupMonitorMocks()
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Then: System hooks should be selected
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, monitorManager.currentMethod.value)
        coVerify { systemLevelMonitor.startMonitoring() }
        verify { systemLevelMonitor.setClipboardListener(monitorManager) }
    }
    
    @Test
    fun `rooted device with xposed selects xposed hooks when system hooks unavailable`() = runTest {
        // Given: Rooted device with only Xposed capability
        setupRootedDevice(hasSystemHooks = false, hasXposed = true)
        setupMonitorMocks()
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Then: System level monitor should be used (handles both system and xposed hooks)
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, monitorManager.currentMethod.value)
        coVerify { systemLevelMonitor.startMonitoring() }
    }
    
    @Test
    fun `non-root device with accessibility service selects accessibility monitoring`() = runTest {
        // Given: Non-root device with accessibility service enabled
        setupNonRootDevice(accessibilityEnabled = true)
        setupMonitorMocks()
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Then: Accessibility monitor should be selected
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitorManager.currentMethod.value)
        coVerify { accessibilityMonitor.startMonitoring() }
        verify { accessibilityMonitor.setClipboardListener(monitorManager) }
    }
    
    @Test
    fun `non-root device without accessibility falls back to foreground service`() = runTest {
        // Given: Non-root device without accessibility service
        setupNonRootDevice(accessibilityEnabled = false)
        setupMonitorMocks()
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Then: Foreground service should be selected
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.FOREGROUND_SERVICE, monitorManager.currentMethod.value)
        coVerify { foregroundServiceMonitor.startMonitoring() }
        verify { foregroundServiceMonitor.setClipboardListener(monitorManager) }
    }
    
    @Test
    fun `fallback chain executes when optimal strategy fails`() = runTest {
        // Given: Rooted device where system hooks fail
        setupRootedDevice(hasSystemHooks = true, hasXposed = false)
        setupMonitorMocks()
        
        // System hooks fail, but accessibility service works
        coEvery { systemLevelMonitor.startMonitoring() } throws RuntimeException("System hooks failed")
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns true
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Give time for fallback chain to execute
        delay(100)
        
        // Then: Should fall back to accessibility service
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitorManager.currentMethod.value)
        coVerify { systemLevelMonitor.startMonitoring() }
        coVerify { accessibilityMonitor.startMonitoring() }
    }
    
    @Test
    fun `fallback chain continues to polling when all other methods fail`() = runTest {
        // Given: Device where all preferred methods fail
        setupNonRootDevice(accessibilityEnabled = false)
        setupMonitorMocks()
        
        // All methods except polling fail
        coEvery { foregroundServiceMonitor.startMonitoring() } throws RuntimeException("Foreground service failed")
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Give time for fallback chain to execute
        delay(200)
        
        // Then: Should fall back to polling
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.POLLING_FALLBACK, monitorManager.currentMethod.value)
        coVerify { foregroundServiceMonitor.startMonitoring() }
        coVerify { pollingMonitor.startMonitoring() }
    }
    
    @Test
    fun `clipboard events are forwarded through manager to external listener`() = runTest {
        // Given: Manager is monitoring with accessibility service
        setupNonRootDevice(accessibilityEnabled = true)
        setupMonitorMocks()
        
        monitorManager.startMonitoring(testListener)
        
        // When: Clipboard change occurs
        val content = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "test content".toByteArray(),
            mimeType = "text/plain",
            timestamp = System.currentTimeMillis(),
            source = "test",
            size = 12L
        )
        val timestamp = System.currentTimeMillis()
        
        monitorManager.onClipboardChanged(content, timestamp)
        
        // Then: External listener should receive the event
        assertEquals(1, testListener.clipboardChanges.size)
        assertEquals(content, testListener.clipboardChanges[0].first)
        assertEquals(timestamp, testListener.clipboardChanges[0].second)
    }
    
    @Test
    fun `monitoring errors are handled and forwarded to external listener`() = runTest {
        // Given: Manager is monitoring
        setupNonRootDevice(accessibilityEnabled = true)
        setupMonitorMocks()
        
        monitorManager.startMonitoring(testListener)
        
        // When: Monitoring error occurs
        val error = ClipboardError.AccessibilityServiceUnavailable
        monitorManager.onMonitoringError(error)
        
        // Then: Error should be handled and forwarded
        assertEquals(1, testListener.errors.size)
        assertEquals(error, testListener.errors[0])
        coVerify { errorHandler.handleError(error, MonitoringMethod.ACCESSIBILITY_SERVICE) }
    }
    
    @Test
    fun `switching monitoring method preserves state and listener`() = runTest {
        // Given: Manager is monitoring with accessibility service
        setupNonRootDevice(accessibilityEnabled = true)
        setupMonitorMocks()
        
        monitorManager.startMonitoring(testListener)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitorManager.currentMethod.value)
        
        // When: Switching to foreground service
        monitorManager.switchMonitoringMethod(MonitoringMethod.FOREGROUND_SERVICE)
        
        // Then: Should switch successfully and preserve listener
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.FOREGROUND_SERVICE, monitorManager.currentMethod.value)
        
        // Verify old monitor was stopped and new one started
        coVerify { accessibilityMonitor.stopMonitoring() }
        coVerify { foregroundServiceMonitor.startMonitoring() }
        verify { foregroundServiceMonitor.setClipboardListener(monitorManager) }
        
        // Test that events still work with new monitor
        val content = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "test".toByteArray(),
            mimeType = "text/plain",
            timestamp = System.currentTimeMillis(),
            source = "test",
            size = 4L
        )
        
        monitorManager.onClipboardChanged(content, System.currentTimeMillis())
        assertEquals(1, testListener.clipboardChanges.size)
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.Q]) // Android 10
    fun `android 10 device prioritizes accessibility service over foreground service`() = runTest {
        // Given: Android 10 device with accessibility service enabled
        setupNonRootDevice(accessibilityEnabled = true)
        setupMonitorMocks()
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Then: Accessibility service should be selected (higher priority on Android 10+)
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitorManager.currentMethod.value)
        coVerify { accessibilityMonitor.startMonitoring() }
        coVerify(exactly = 0) { foregroundServiceMonitor.startMonitoring() }
    }
    
    private fun setupRootedDevice(hasSystemHooks: Boolean, hasXposed: Boolean) {
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = hasSystemHooks,
            hasXposedFramework = hasXposed,
            hasNativeAccess = hasSystemHooks,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        
        coEvery { rootDetectionService.isRooted() } returns true
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns false
        every { anyConstructed<AccessibilityPermissionManager>().isServiceActiveAndMonitoring() } returns false
    }
    
    private fun setupNonRootDevice(accessibilityEnabled: Boolean) {
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE,
            suBinaryPath = null,
            isRootAccessible = false
        )
        
        coEvery { rootDetectionService.isRooted() } returns false
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns accessibilityEnabled
        every { anyConstructed<AccessibilityPermissionManager>().isServiceActiveAndMonitoring() } returns accessibilityEnabled
    }
    
    private fun setupMonitorMocks() {
        // Setup successful monitor starts by default
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        coEvery { systemLevelMonitor.stopMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        
        coEvery { accessibilityMonitor.startMonitoring() } just Runs
        coEvery { accessibilityMonitor.stopMonitoring() } just Runs
        every { accessibilityMonitor.getMonitoringMethod() } returns MonitoringMethod.ACCESSIBILITY_SERVICE
        
        coEvery { foregroundServiceMonitor.startMonitoring() } just Runs
        coEvery { foregroundServiceMonitor.stopMonitoring() } just Runs
        every { foregroundServiceMonitor.getMonitoringMethod() } returns MonitoringMethod.FOREGROUND_SERVICE
        
        coEvery { pollingMonitor.startMonitoring() } just Runs
        coEvery { pollingMonitor.stopMonitoring() } just Runs
        every { pollingMonitor.getMonitoringMethod() } returns MonitoringMethod.POLLING_FALLBACK
    }
    
    /**
     * Test implementation of ClipboardListener for capturing events.
     */
    private class TestClipboardListener : ClipboardListener {
        val clipboardChanges = mutableListOf<Pair<ClipboardContent, Long>>()
        val errors = mutableListOf<ClipboardError>()
        
        override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {
            clipboardChanges.add(content to timestamp)
        }
        
        override suspend fun onMonitoringError(error: ClipboardError) {
            errors.add(error)
        }
    }
}