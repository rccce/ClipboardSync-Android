package com.siw.clipboardsync.monitor

import android.content.Context
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.RootCapabilities
import com.siw.clipboardsync.service.RootDetectionService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SystemLevelClipboardMonitor.
 */
@ExperimentalCoroutinesApi
class SystemLevelClipboardMonitorTest {
    
    private lateinit var context: Context
    private lateinit var rootDetectionService: RootDetectionService
    private lateinit var timingOptimizer: TimingOptimizer
    private lateinit var nativeHookManager: NativeHookManager
    private lateinit var xposedHookManager: XposedHookManager
    private lateinit var clipboardListener: ClipboardListener
    private lateinit var systemLevelMonitor: SystemLevelClipboardMonitor
    
    @Before
    fun setUp() {
        context = mockk()
        rootDetectionService = mockk()
        timingOptimizer = mockk()
        nativeHookManager = mockk()
        xposedHookManager = mockk()
        clipboardListener = mockk()
        
        systemLevelMonitor = SystemLevelClipboardMonitor(
            context = context,
            rootDetectionService = rootDetectionService,
            timingOptimizer = timingOptimizer,
            nativeHookManager = nativeHookManager,
            xposedHookManager = xposedHookManager
        )
        
        systemLevelMonitor.setClipboardListener(clipboardListener)
        
        // Default mock behaviors
        every { timingOptimizer.getOptimalReadDelay() } returns 100L
        every { timingOptimizer.shouldDebounce(any()) } returns false
        every { timingOptimizer.getRetryDelay(any()) } returns 1000L
        coEvery { clipboardListener.onClipboardChanged(any(), any()) } just Runs
        coEvery { clipboardListener.onMonitoringError(any()) } just Runs
    }
    
    @After
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `startMonitoring with native hooks available should succeed`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { nativeHookManager.registerClipboardCallback(any()) } just Runs
        
        // When
        systemLevelMonitor.startMonitoring()
        
        // Then
        assertTrue(systemLevelMonitor.isMonitoring())
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, systemLevelMonitor.getMonitoringMethod())
        verify { nativeHookManager.registerClipboardCallback(any()) }
    }
    
    @Test
    fun `startMonitoring with xposed hooks available should succeed`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = true,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns false
        every { xposedHookManager.isAvailable() } returns true
        every { xposedHookManager.hookClipboardService(any()) } just Runs
        
        // When
        systemLevelMonitor.startMonitoring()
        
        // Then
        assertTrue(systemLevelMonitor.isMonitoring())
        assertEquals(MonitoringMethod.XPOSED_HOOKS, systemLevelMonitor.getMonitoringMethod())
        verify { xposedHookManager.hookClipboardService(any()) }
    }
    
    @Test
    fun `startMonitoring with no system hooks should throw exception`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        
        // When & Then
        try {
            systemLevelMonitor.startMonitoring()
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            assertEquals("SYSTEM_HOOK_FAILED", e.errorCode)
            assertFalse(systemLevelMonitor.isMonitoring())
        }
    }
    
    @Test
    fun `startMonitoring when already monitoring should log warning`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { nativeHookManager.registerClipboardCallback(any()) } just Runs
        
        // Start monitoring first time
        systemLevelMonitor.startMonitoring()
        
        // When - try to start again
        systemLevelMonitor.startMonitoring()
        
        // Then - should still be monitoring with same method
        assertTrue(systemLevelMonitor.isMonitoring())
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, systemLevelMonitor.getMonitoringMethod())
    }
    
    @Test
    fun `stopMonitoring should cleanup native hooks`() = runTest {
        // Given - start monitoring first
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { nativeHookManager.registerClipboardCallback(any()) } just Runs
        every { nativeHookManager.unregisterClipboardCallback() } just Runs
        
        systemLevelMonitor.startMonitoring()
        assertTrue(systemLevelMonitor.isMonitoring())
        
        // When
        systemLevelMonitor.stopMonitoring()
        
        // Then
        assertFalse(systemLevelMonitor.isMonitoring())
        verify { nativeHookManager.unregisterClipboardCallback() }
    }
    
    @Test
    fun `stopMonitoring should cleanup xposed hooks`() = runTest {
        // Given - start monitoring first
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = true,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns false
        every { xposedHookManager.isAvailable() } returns true
        every { xposedHookManager.hookClipboardService(any()) } just Runs
        every { xposedHookManager.unhookClipboardService() } just Runs
        
        systemLevelMonitor.startMonitoring()
        assertTrue(systemLevelMonitor.isMonitoring())
        
        // When
        systemLevelMonitor.stopMonitoring()
        
        // Then
        assertFalse(systemLevelMonitor.isMonitoring())
        verify { xposedHookManager.unhookClipboardService() }
    }
    
    @Test
    fun `stopMonitoring when not monitoring should log warning`() = runTest {
        // Given - not monitoring
        assertFalse(systemLevelMonitor.isMonitoring())
        
        // When
        systemLevelMonitor.stopMonitoring()
        
        // Then - should remain not monitoring
        assertFalse(systemLevelMonitor.isMonitoring())
    }
    
    @Test
    fun `isSystemLevelMonitoringAvailable should return true when native hooks available`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { xposedHookManager.isAvailable() } returns false
        
        // When
        val result = systemLevelMonitor.isSystemLevelMonitoringAvailable()
        
        // Then
        assertTrue(result)
    }
    
    @Test
    fun `isSystemLevelMonitoringAvailable should return true when xposed hooks available`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = true,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns false
        every { xposedHookManager.isAvailable() } returns true
        
        // When
        val result = systemLevelMonitor.isSystemLevelMonitoringAvailable()
        
        // Then
        assertTrue(result)
    }
    
    @Test
    fun `isSystemLevelMonitoringAvailable should return false when no hooks available`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns false
        every { xposedHookManager.isAvailable() } returns false
        
        // When
        val result = systemLevelMonitor.isSystemLevelMonitoringAvailable()
        
        // Then
        assertFalse(result)
    }
    
    @Test
    fun `getSystemCapabilities should return correct capability map`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = true,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.isRooted() } returns true
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { xposedHookManager.isAvailable() } returns false
        
        // When
        val capabilities = systemLevelMonitor.getSystemCapabilities()
        
        // Then
        assertEquals(true, capabilities["hasRoot"])
        assertEquals(true, capabilities["hasSystemHooks"])
        assertEquals(true, capabilities["hasXposedFramework"])
        assertEquals(true, capabilities["nativeHooksAvailable"])
        assertEquals(false, capabilities["xposedHooksAvailable"])
    }
    
    @Test
    fun `native hook failure should fallback to xposed`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = true,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { nativeHookManager.registerClipboardCallback(any()) } throws RuntimeException("Native hook failed")
        every { xposedHookManager.isAvailable() } returns true
        every { xposedHookManager.hookClipboardService(any()) } just Runs
        
        // When
        systemLevelMonitor.startMonitoring()
        
        // Then
        assertTrue(systemLevelMonitor.isMonitoring())
        assertEquals(MonitoringMethod.XPOSED_HOOKS, systemLevelMonitor.getMonitoringMethod())
        verify { xposedHookManager.hookClipboardService(any()) }
        coVerify { clipboardListener.onMonitoringError(any()) }
    }
    
    @Test
    fun `clipboard change should be processed with timing optimization`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.OTHER
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        
        val capturedCallback = slot<(ClipboardContent) -> Unit>()
        every { nativeHookManager.registerClipboardCallback(capture(capturedCallback)) } just Runs
        
        val adaptiveOptimizer = mockk<AdaptiveTimingOptimizer>()
        every { adaptiveOptimizer.getOptimalReadDelay() } returns 50L
        every { adaptiveOptimizer.shouldDebounce(any()) } returns false
        every { adaptiveOptimizer.updateLastChangeTimestamp(any()) } just Runs
        every { adaptiveOptimizer.recordSuccess() } just Runs
        
        val monitorWithAdaptiveOptimizer = SystemLevelClipboardMonitor(
            context = context,
            rootDetectionService = rootDetectionService,
            timingOptimizer = adaptiveOptimizer,
            nativeHookManager = nativeHookManager,
            xposedHookManager = xposedHookManager
        )
        monitorWithAdaptiveOptimizer.setClipboardListener(clipboardListener)
        
        // Start monitoring
        monitorWithAdaptiveOptimizer.startMonitoring()
        
        // When - simulate clipboard change
        val testContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "test".toByteArray(),
            mimeType = "text/plain",
            source = "test",
            size = 4L
        )
        
        capturedCallback.captured.invoke(testContent)
        
        // Give some time for coroutine to execute
        kotlinx.coroutines.delay(100)
        
        // Then
        verify { adaptiveOptimizer.updateLastChangeTimestamp(any()) }
        verify { adaptiveOptimizer.recordSuccess() }
        coVerify { clipboardListener.onClipboardChanged(testContent, any()) }
    }
}