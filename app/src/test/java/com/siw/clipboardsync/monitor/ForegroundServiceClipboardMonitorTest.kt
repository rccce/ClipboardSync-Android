package com.siw.clipboardsync.monitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.ForegroundClipboardService
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ForegroundServiceClipboardMonitorTest {
    
    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var monitor: ForegroundServiceClipboardMonitor
    private lateinit var mockListener: ClipboardListener
    
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        context = mockk(relaxed = true)
        clipboardManager = mockk(relaxed = true)
        mockListener = mockk(relaxed = true)
        
        monitor = ForegroundServiceClipboardMonitor(context, clipboardManager)
        monitor.setClipboardListener(mockListener)
        
        // Mock service starting
        every { context.startForegroundService(any()) } returns mockk()
        every { context.startService(any()) } returns mockk()
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    @Test
    fun `startMonitoring should start foreground service and begin monitoring`() = runTest {
        // Given
        val clipData = mockk<ClipData>()
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0).text } returns "initial content"
        every { clipboardManager.primaryClip } returns clipData
        
        // When
        monitor.startMonitoring()
        
        // Then
        assertTrue(monitor.isMonitoring())
        assertEquals(MonitoringMethod.FOREGROUND_SERVICE, monitor.getMonitoringMethod())
        
        // Verify service was started
        verify {
            context.startForegroundService(match { intent ->
                intent.action == ForegroundClipboardService.ACTION_START_MONITORING
            })
        }
    }
    
    @Test
    fun `startMonitoring should handle service start failure`() = runTest {
        // Given
        val exception = RuntimeException("Service start failed")
        every { context.startForegroundService(any()) } throws exception
        
        // When & Then
        try {
            monitor.startMonitoring()
            assert(false) { "Expected ClipboardMonitorException" }
        } catch (e: ClipboardMonitorException) {
            assertEquals("Failed to start foreground service monitoring", e.message)
            assertEquals(exception, e.cause)
        }
        
        // Verify error was reported to listener
        coVerify {
            mockListener.onMonitoringError(match { error ->
                error is ClipboardError.ForegroundServiceFailed && error.cause == exception
            })
        }
    }
    
    @Test
    fun `stopMonitoring should stop foreground service and monitoring`() = runTest {
        // Given
        monitor.startMonitoring()
        assertTrue(monitor.isMonitoring())
        
        // When
        monitor.stopMonitoring()
        
        // Then
        assertFalse(monitor.isMonitoring())
        
        // Verify service was stopped
        verify {
            context.startService(match { intent ->
                intent.action == ForegroundClipboardService.ACTION_STOP_MONITORING
            })
        }
    }
    
    @Test
    fun `stopMonitoring should handle service stop failure gracefully`() = runTest {
        // Given
        monitor.startMonitoring()
        val exception = RuntimeException("Service stop failed")
        every { context.startService(any()) } throws exception
        
        // When
        monitor.stopMonitoring()
        
        // Then
        assertFalse(monitor.isMonitoring())
        
        // Verify error was reported to listener
        coVerify {
            mockListener.onMonitoringError(match { error ->
                error is ClipboardError.ForegroundServiceFailed && error.cause == exception
            })
        }
    }
    
    @Test
    fun `clipboard change detection should notify listener`() = runTest {
        // Given
        val initialClipData = mockk<ClipData>()
        every { initialClipData.itemCount } returns 1
        every { initialClipData.getItemAt(0).text } returns "initial content"
        
        val newClipData = mockk<ClipData>()
        every { newClipData.itemCount } returns 1
        every { newClipData.getItemAt(0).text } returns "new content"
        
        // Setup clipboard manager to return different content on subsequent calls
        every { clipboardManager.primaryClip } returnsMany listOf(initialClipData, newClipData)
        
        // When
        monitor.startMonitoring()
        
        // Advance time to trigger clipboard check
        testDispatcher.scheduler.advanceTimeBy(1100L) // Just over 1 second
        testDispatcher.scheduler.runCurrent()
        
        // Then
        coVerify {
            mockListener.onClipboardChanged(
                match { content ->
                    content.type == ClipboardContent.ContentType.TEXT &&
                    String(content.data, Charsets.UTF_8) == "new content" &&
                    content.source == "foreground_service"
                },
                any()
            )
        }
    }
    
    @Test
    fun `clipboard change should be debounced within 200ms window`() = runTest {
        // Given
        val clipData1 = mockk<ClipData>()
        every { clipData1.itemCount } returns 1
        every { clipData1.getItemAt(0).text } returns "content1"
        
        val clipData2 = mockk<ClipData>()
        every { clipData2.itemCount } returns 1
        every { clipData2.getItemAt(0).text } returns "content2"
        
        every { clipboardManager.primaryClip } returnsMany listOf(clipData1, clipData2)
        
        // When
        monitor.startMonitoring()
        
        // Advance time by less than debounce window
        testDispatcher.scheduler.advanceTimeBy(150L)
        testDispatcher.scheduler.runCurrent()
        
        // Then - should not trigger callback due to debouncing
        coVerify(exactly = 0) {
            mockListener.onClipboardChanged(any(), any())
        }
    }
    
    @Test
    fun `clipboard access denied should report permission error`() = runTest {
        // Given
        val securityException = SecurityException("Clipboard access denied")
        every { clipboardManager.primaryClip } throws securityException
        
        // When
        monitor.startMonitoring()
        
        // Advance time to trigger clipboard check
        testDispatcher.scheduler.advanceTimeBy(1100L)
        testDispatcher.scheduler.runCurrent()
        
        // Then
        coVerify {
            mockListener.onMonitoringError(match { error ->
                error is ClipboardError.PermissionDenied &&
                error.permission == "android.permission.READ_CLIPBOARD"
            })
        }
    }
    
    @Test
    fun `empty clipboard should not trigger change notification`() = runTest {
        // Given
        every { clipboardManager.primaryClip } returns null
        
        // When
        monitor.startMonitoring()
        
        // Advance time to trigger clipboard check
        testDispatcher.scheduler.advanceTimeBy(1100L)
        testDispatcher.scheduler.runCurrent()
        
        // Then
        coVerify(exactly = 0) {
            mockListener.onClipboardChanged(any(), any())
        }
    }
    
    @Test
    fun `blank clipboard content should not trigger change notification`() = runTest {
        // Given
        val clipData = mockk<ClipData>()
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0).text } returns "   " // Blank content
        every { clipboardManager.primaryClip } returns clipData
        
        // When
        monitor.startMonitoring()
        
        // Advance time to trigger clipboard check
        testDispatcher.scheduler.advanceTimeBy(1100L)
        testDispatcher.scheduler.runCurrent()
        
        // Then
        coVerify(exactly = 0) {
            mockListener.onClipboardChanged(any(), any())
        }
    }
    
    @Test
    fun `same clipboard content should not trigger duplicate notifications`() = runTest {
        // Given
        val clipData = mockk<ClipData>()
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0).text } returns "same content"
        every { clipboardManager.primaryClip } returns clipData
        
        // When
        monitor.startMonitoring()
        
        // Advance time multiple times
        repeat(3) {
            testDispatcher.scheduler.advanceTimeBy(1100L)
            testDispatcher.scheduler.runCurrent()
        }
        
        // Then - should only trigger once for the initial change
        coVerify(exactly = 0) {
            mockListener.onClipboardChanged(any(), any())
        }
    }
    
    @Test
    fun `monitoring method should return FOREGROUND_SERVICE`() {
        // When & Then
        assertEquals(MonitoringMethod.FOREGROUND_SERVICE, monitor.getMonitoringMethod())
    }
    
    @Test
    fun `isMonitoring should return false initially`() {
        // When & Then
        assertFalse(monitor.isMonitoring())
    }
    
    @Test
    fun `startMonitoring when already monitoring should not start again`() = runTest {
        // Given
        monitor.startMonitoring()
        clearMocks(context)
        
        // When
        monitor.startMonitoring()
        
        // Then
        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.startService(any()) }
    }
    
    @Test
    fun `stopMonitoring when not monitoring should handle gracefully`() = runTest {
        // Given - monitor not started
        assertFalse(monitor.isMonitoring())
        
        // When
        monitor.stopMonitoring()
        
        // Then - should not crash and remain not monitoring
        assertFalse(monitor.isMonitoring())
    }
    
    @Test
    fun `clipboard content metadata should include monitoring method and android version`() = runTest {
        // Given
        val clipData = mockk<ClipData>()
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0).text } returns "test content"
        every { clipboardManager.primaryClip } returnsMany listOf(null, clipData)
        
        // When
        monitor.startMonitoring()
        
        // Advance time to trigger clipboard check
        testDispatcher.scheduler.advanceTimeBy(1100L)
        testDispatcher.scheduler.runCurrent()
        
        // Then
        coVerify {
            mockListener.onClipboardChanged(
                match { content ->
                    content.metadata["monitoring_method"] == "foreground_service" &&
                    content.metadata.containsKey("android_version")
                },
                any()
            )
        }
    }
}