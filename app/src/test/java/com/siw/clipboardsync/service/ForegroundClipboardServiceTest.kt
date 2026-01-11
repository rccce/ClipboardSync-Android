package com.siw.clipboardsync.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.siw.clipboardsync.service.ForegroundClipboardService.Companion.ACTION_RESTART_SERVICE
import com.siw.clipboardsync.service.ForegroundClipboardService.Companion.ACTION_START_MONITORING
import com.siw.clipboardsync.service.ForegroundClipboardService.Companion.ACTION_STOP_MONITORING
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ForegroundClipboardServiceTest {
    
    private lateinit var service: ForegroundClipboardService
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockCallback: ForegroundClipboardService.ServiceCallback
    private lateinit var testLifecycleOwner: LifecycleOwner
    
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        mockNotificationManager = mockk(relaxed = true)
        mockCallback = mockk(relaxed = true)
        testLifecycleOwner = mockk(relaxed = true)
        
        // Create service using Robolectric
        val serviceController = Robolectric.buildService(ForegroundClipboardService::class.java)
        service = serviceController.create().get()
        
        // Mock system services
        mockkStatic("android.content.Context")
        every { service.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        
        service.registerCallback(mockCallback)
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }
    
    @Test
    fun `service should start monitoring on START_MONITORING action`() {
        // Given
        val intent = Intent().apply { action = ACTION_START_MONITORING }
        
        // When
        val result = service.onStartCommand(intent, 0, 1)
        
        // Then
        assertEquals(android.app.Service.START_STICKY, result)
        assertTrue(service.isRunning())
        
        // Verify callback was called
        verify { mockCallback.onServiceStarted() }
    }
    
    @Test
    fun `service should stop monitoring on STOP_MONITORING action`() {
        // Given
        val startIntent = Intent().apply { action = ACTION_START_MONITORING }
        service.onStartCommand(startIntent, 0, 1)
        assertTrue(service.isRunning())
        
        val stopIntent = Intent().apply { action = ACTION_STOP_MONITORING }
        
        // When
        service.onStartCommand(stopIntent, 0, 2)
        
        // Then
        assertFalse(service.isRunning())
    }
    
    @Test
    fun `service should restart on RESTART_SERVICE action`() = runTest {
        // Given
        val startIntent = Intent().apply { action = ACTION_START_MONITORING }
        service.onStartCommand(startIntent, 0, 1)
        assertTrue(service.isRunning())
        
        val restartIntent = Intent().apply { action = ACTION_RESTART_SERVICE }
        
        // When
        service.onStartCommand(restartIntent, 0, 2)
        
        // Advance time to allow restart coroutine to complete
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then
        assertTrue(service.isRunning())
        
        // Verify callbacks were called (stop then start)
        verify(atLeast = 1) { mockCallback.onServiceStarted() }
    }
    
    @Test
    fun `service should handle unknown action by starting monitoring`() {
        // Given
        val intent = Intent().apply { action = "UNKNOWN_ACTION" }
        
        // When
        val result = service.onStartCommand(intent, 0, 1)
        
        // Then
        assertEquals(android.app.Service.START_STICKY, result)
        assertTrue(service.isRunning())
    }
    
    @Test
    fun `service should handle null intent by starting monitoring`() {
        // Given
        val intent: Intent? = null
        
        // When
        val result = service.onStartCommand(intent, 0, 1)
        
        // Then
        assertEquals(android.app.Service.START_STICKY, result)
        assertTrue(service.isRunning())
    }
    
    @Test
    fun `service should return binder on bind`() {
        // When
        val binder = service.onBind(Intent())
        
        // Then
        assertTrue(binder is ForegroundClipboardService.ForegroundClipboardBinder)
        assertEquals(service, (binder as ForegroundClipboardService.ForegroundClipboardBinder).getService())
    }
    
    @Test
    fun `service should handle lifecycle changes correctly`() {
        // Given
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 1)
        
        // When - app goes to foreground
        service.onStart(testLifecycleOwner)
        
        // Then - notification should be updated
        verify { mockNotificationManager.notify(eq(ForegroundClipboardService.NOTIFICATION_ID), any()) }
        
        // When - app goes to background
        service.onStop(testLifecycleOwner)
        
        // Then - notification should be updated again
        verify(atLeast = 2) { mockNotificationManager.notify(eq(ForegroundClipboardService.NOTIFICATION_ID), any()) }
    }
    
    @Test
    fun `service should attempt auto-restart on failure`() = runTest {
        // Given
        val exception = RuntimeException("Service failure")
        
        // Mock startForeground to throw exception
        mockkObject(service)
        every { service.startForeground(any(), any()) } throws exception
        
        // When
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 1)
        
        // Advance time to allow auto-restart attempt
        testDispatcher.scheduler.advanceTimeBy(6000L) // More than RESTART_DELAY_MS
        testDispatcher.scheduler.runCurrent()
        
        // Then
        verify { mockCallback.onServiceError(exception) }
        assertTrue(service.getRestartAttempts() > 0)
    }
    
    @Test
    fun `service should limit auto-restart attempts`() = runTest {
        // Given
        val exception = RuntimeException("Persistent failure")
        
        // Mock startForeground to always throw exception
        mockkObject(service)
        every { service.startForeground(any(), any()) } throws exception
        
        // When - trigger multiple restart attempts
        repeat(5) {
            service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, it + 1)
            testDispatcher.scheduler.advanceTimeBy(6000L)
            testDispatcher.scheduler.runCurrent()
        }
        
        // Then - should not exceed max restart attempts
        assertTrue(service.getRestartAttempts() <= 3) // MAX_RESTART_ATTEMPTS
    }
    
    @Test
    fun `service should register and unregister callbacks correctly`() {
        // Given
        val callback1 = mockk<ForegroundClipboardService.ServiceCallback>(relaxed = true)
        val callback2 = mockk<ForegroundClipboardService.ServiceCallback>(relaxed = true)
        
        // When
        service.registerCallback(callback1)
        service.registerCallback(callback2)
        
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 1)
        
        // Then
        verify { callback1.onServiceStarted() }
        verify { callback2.onServiceStarted() }
        
        // When - unregister one callback
        service.unregisterCallback(callback1)
        service.onStartCommand(Intent().apply { action = ACTION_RESTART_SERVICE }, 0, 2)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then - only callback2 should be called for restart
        verify(atLeast = 1) { callback2.onServiceStarted() }
    }
    
    @Test
    fun `service should handle callback exceptions gracefully`() {
        // Given
        val faultyCallback = mockk<ForegroundClipboardService.ServiceCallback>()
        every { faultyCallback.onServiceStarted() } throws RuntimeException("Callback error")
        
        service.registerCallback(faultyCallback)
        
        // When
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 1)
        
        // Then - service should still start despite callback error
        assertTrue(service.isRunning())
    }
    
    @Test
    fun `service should clean up resources on destroy`() {
        // Given
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 1)
        assertTrue(service.isRunning())
        
        // When
        service.onDestroy()
        
        // Then
        assertFalse(service.isRunning())
        verify { mockCallback.onServiceStopped() }
    }
    
    @Test
    fun `startService should create correct intent for API 26+`() {
        // Given
        val context = mockk<Context>(relaxed = true)
        
        // When
        ForegroundClipboardService.startService(context)
        
        // Then
        verify {
            context.startForegroundService(match { intent ->
                intent.action == ACTION_START_MONITORING &&
                intent.component?.className?.endsWith("ForegroundClipboardService") == true
            })
        }
    }
    
    @Test
    fun `stopService should create correct intent`() {
        // Given
        val context = mockk<Context>(relaxed = true)
        
        // When
        ForegroundClipboardService.stopService(context)
        
        // Then
        verify {
            context.startService(match { intent ->
                intent.action == ACTION_STOP_MONITORING &&
                intent.component?.className?.endsWith("ForegroundClipboardService") == true
            })
        }
    }
    
    @Test
    fun `restartService should create correct intent for API 26+`() {
        // Given
        val context = mockk<Context>(relaxed = true)
        
        // When
        ForegroundClipboardService.restartService(context)
        
        // Then
        verify {
            context.startForegroundService(match { intent ->
                intent.action == ACTION_RESTART_SERVICE &&
                intent.component?.className?.endsWith("ForegroundClipboardService") == true
            })
        }
    }
    
    @Test
    fun `service should not start if already running`() {
        // Given
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 1)
        assertTrue(service.isRunning())
        clearMocks(mockCallback)
        
        // When
        service.onStartCommand(Intent().apply { action = ACTION_START_MONITORING }, 0, 2)
        
        // Then
        verify(exactly = 0) { mockCallback.onServiceStarted() }
    }
    
    @Test
    fun `service should handle stop when not running gracefully`() {
        // Given
        assertFalse(service.isRunning())
        
        // When
        service.onStartCommand(Intent().apply { action = ACTION_STOP_MONITORING }, 0, 1)
        
        // Then - should not crash
        assertFalse(service.isRunning())
    }
}