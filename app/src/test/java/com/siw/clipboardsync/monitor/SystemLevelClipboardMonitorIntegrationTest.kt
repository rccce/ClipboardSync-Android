package com.siw.clipboardsync.monitor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.RootCapabilities
import com.siw.clipboardsync.monitor.model.TimingConfig
import com.siw.clipboardsync.service.RootDetectionService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for SystemLevelClipboardMonitor.
 * Tests the complete flow of system-level clipboard monitoring.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemLevelClipboardMonitorIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var rootDetectionService: RootDetectionService
    private lateinit var timingOptimizer: AdaptiveTimingOptimizer
    private lateinit var nativeHookManager: NativeHookManager
    private lateinit var xposedHookManager: XposedHookManager
    private lateinit var systemLevelMonitor: SystemLevelClipboardMonitor
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rootDetectionService = mockk()
        
        // Use real timing optimizer for integration tests
        timingOptimizer = AdaptiveTimingOptimizer(
            context = context,
            timingConfig = TimingConfig(
                readDelayMs = 50L,
                writeDelayMs = 25L,
                debounceWindowMs = 100L,
                retryDelayMs = 500L,
                maxRetries = 2
            )
        )
        
        nativeHookManager = mockk()
        xposedHookManager = mockk()
        
        systemLevelMonitor = SystemLevelClipboardMonitor(
            context = context,
            rootDetectionService = rootDetectionService,
            timingOptimizer = timingOptimizer,
            nativeHookManager = nativeHookManager,
            xposedHookManager = xposedHookManager
        )
    }
    
    @After
    fun tearDown() {
        runTest {
            if (systemLevelMonitor.isMonitoring()) {
                systemLevelMonitor.stopMonitoring()
            }
        }
        clearAllMocks()
    }
    
    @Test
    fun `complete monitoring lifecycle with native hooks`() = runTest {
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
        every { nativeHookManager.unregisterClipboardCallback() } just Runs
        
        val clipboardChanges = mutableListOf<ClipboardContent>()
        val errors = mutableListOf<ClipboardError>()
        
        val listener = object : ClipboardListener {
            override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {
                clipboardChanges.add(content)
            }
            
            override suspend fun onMonitoringError(error: ClipboardError) {
                errors.add(error)
            }
        }
        
        systemLevelMonitor.setClipboardListener(listener)
        
        // When - Start monitoring
        systemLevelMonitor.startMonitoring()
        
        // Then - Should be monitoring with native hooks
        assertTrue(systemLevelMonitor.isMonitoring())
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, systemLevelMonitor.getMonitoringMethod())
        verify { nativeHookManager.registerClipboardCallback(any()) }
        
        // When - Stop monitoring
        systemLevelMonitor.stopMonitoring()
        
        // Then - Should stop monitoring and cleanup
        assertFalse(systemLevelMonitor.isMonitoring())
        verify { nativeHookManager.unregisterClipboardCallback() }
        
        // Should have no errors during normal operation
        assertTrue(errors.isEmpty())
    }
    
    @Test
    fun `fallback from native to xposed hooks on failure`() = runTest {
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
        
        val errors = mutableListOf<ClipboardError>()
        val listener = object : ClipboardListener {
            override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {}
            override suspend fun onMonitoringError(error: ClipboardError) {
                errors.add(error)
            }
        }
        
        systemLevelMonitor.setClipboardListener(listener)
        
        // When
        systemLevelMonitor.startMonitoring()
        
        // Then - Should fallback to Xposed hooks
        assertTrue(systemLevelMonitor.isMonitoring())
        assertEquals(MonitoringMethod.XPOSED_HOOKS, systemLevelMonitor.getMonitoringMethod())
        verify { xposedHookManager.hookClipboardService(any()) }
        
        // Should have recorded the native hook failure
        assertTrue(errors.any { it is ClipboardError.SystemHookFailed })
    }
    
    @Test
    fun `clipboard change processing with timing optimization`() = runTest {
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
        
        val clipboardChanges = mutableListOf<Pair<ClipboardContent, Long>>()
        val latch = CountDownLatch(1)
        
        val listener = object : ClipboardListener {
            override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {
                clipboardChanges.add(content to timestamp)
                latch.countDown()
            }
            
            override suspend fun onMonitoringError(error: ClipboardError) {}
        }
        
        systemLevelMonitor.setClipboardListener(listener)
        systemLevelMonitor.startMonitoring()
        
        // When - Simulate clipboard change
        val testContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "integration test content".toByteArray(),
            mimeType = "text/plain",
            source = "integration_test",
            size = "integration test content".length.toLong()
        )
        
        val changeTime = System.currentTimeMillis()
        capturedCallback.captured.invoke(testContent)
        
        // Wait for processing
        assertTrue("Clipboard change should be processed within 2 seconds", 
                  latch.await(2, TimeUnit.SECONDS))
        
        // Then
        assertEquals(1, clipboardChanges.size)
        val (receivedContent, receivedTimestamp) = clipboardChanges.first()
        assertEquals(testContent, receivedContent)
        assertTrue("Timestamp should be after change time", receivedTimestamp >= changeTime)
    }
    
    @Test
    fun `debouncing prevents rapid clipboard changes`() = runTest {
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
        
        val clipboardChanges = mutableListOf<ClipboardContent>()
        val listener = object : ClipboardListener {
            override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {
                clipboardChanges.add(content)
            }
            
            override suspend fun onMonitoringError(error: ClipboardError) {}
        }
        
        systemLevelMonitor.setClipboardListener(listener)
        systemLevelMonitor.startMonitoring()
        
        // When - Send rapid clipboard changes
        val content1 = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "content1".toByteArray(),
            mimeType = "text/plain",
            source = "test",
            size = 8L
        )
        
        val content2 = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "content2".toByteArray(),
            mimeType = "text/plain",
            source = "test",
            size = 8L
        )
        
        capturedCallback.captured.invoke(content1)
        // Send second change immediately (should be debounced)
        capturedCallback.captured.invoke(content2)
        
        // Wait for processing
        delay(200)
        
        // Then - Only first change should be processed due to debouncing
        assertEquals(1, clipboardChanges.size)
        assertEquals(content1, clipboardChanges.first())
    }
    
    @Test
    fun `error handling during clipboard processing`() = runTest {
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
        
        val errors = mutableListOf<ClipboardError>()
        val listener = object : ClipboardListener {
            override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {
                throw RuntimeException("Processing error")
            }
            
            override suspend fun onMonitoringError(error: ClipboardError) {
                errors.add(error)
            }
        }
        
        systemLevelMonitor.setClipboardListener(listener)
        systemLevelMonitor.startMonitoring()
        
        // When - Simulate clipboard change that causes processing error
        val testContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "error test".toByteArray(),
            mimeType = "text/plain",
            source = "test",
            size = 10L
        )
        
        capturedCallback.captured.invoke(testContent)
        
        // Wait for error processing
        delay(200)
        
        // Then - Should have recorded timing optimization error
        assertTrue(errors.any { it is ClipboardError.TimingOptimizationFailed })
    }
    
    @Test
    fun `system capabilities reporting`() = runTest {
        // Given
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.MAGISK
        )
        coEvery { rootDetectionService.isRooted() } returns true
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { nativeHookManager.isAvailable() } returns true
        every { xposedHookManager.isAvailable() } returns false
        
        // When
        val capabilities = systemLevelMonitor.getSystemCapabilities()
        val isAvailable = systemLevelMonitor.isSystemLevelMonitoringAvailable()
        
        // Then
        assertTrue(isAvailable)
        assertEquals(true, capabilities["hasRoot"])
        assertEquals(true, capabilities["hasSystemHooks"])
        assertEquals(false, capabilities["hasXposedFramework"])
        assertEquals(true, capabilities["nativeHooksAvailable"])
        assertEquals(false, capabilities["xposedHooksAvailable"])
    }
    
    @Test
    fun `monitoring unavailable when no system hooks`() = runTest {
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
        
        val errors = mutableListOf<ClipboardError>()
        val listener = object : ClipboardListener {
            override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {}
            override suspend fun onMonitoringError(error: ClipboardError) {
                errors.add(error)
            }
        }
        
        systemLevelMonitor.setClipboardListener(listener)
        
        // When & Then
        try {
            systemLevelMonitor.startMonitoring()
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            assertEquals("SYSTEM_HOOK_FAILED", e.errorCode)
            assertFalse(systemLevelMonitor.isMonitoring())
            assertFalse(systemLevelMonitor.isSystemLevelMonitoringAvailable())
        }
        
        // Should have notified listener of error
        assertTrue(errors.any { it is ClipboardError.SystemHookFailed })
    }
}