package com.siw.clipboardsync.monitor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.ClipboardAccessibilityService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityClipboardMonitorTest {
    
    private lateinit var context: Context
    private lateinit var monitor: AccessibilityClipboardMonitor
    private lateinit var mockListener: ClipboardListener
    
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockListener = mockk(relaxed = true)
        
        // Mock Settings.Secure access
        mockkStatic(Settings.Secure::class)
        
        monitor = AccessibilityClipboardMonitor(context)
        monitor.setClipboardListener(mockListener)
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `getMonitoringMethod returns ACCESSIBILITY_SERVICE`() {
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitor.getMonitoringMethod())
    }
    
    @Test
    fun `isMonitoring returns false initially`() {
        assertFalse(monitor.isMonitoring())
    }
    
    @Test
    fun `startMonitoring fails when accessibility service is not enabled`() = runTest {
        // Given: Accessibility service is not enabled
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } returns 0
        
        // When: Starting monitoring
        try {
            monitor.startMonitoring()
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            // Then: Should throw exception with correct error
            assertEquals(ClipboardError.AccessibilityServiceUnavailable, e.error)
            assertFalse(monitor.isMonitoring())
        }
    }
    
    @Test
    fun `startMonitoring fails when service is enabled but app service is not in enabled list`() = runTest {
        // Given: Accessibility is enabled but our service is not in the list
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } returns 1
        
        every {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } returns "com.other.app/com.other.app.SomeService"
        
        every { context.packageName } returns "com.siw.clipboardsync"
        
        // When: Starting monitoring
        try {
            monitor.startMonitoring()
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            // Then: Should throw exception
            assertEquals(ClipboardError.AccessibilityServiceUnavailable, e.error)
            assertFalse(monitor.isMonitoring())
        }
    }
    
    @Test
    fun `startMonitoring succeeds when accessibility service is properly enabled`() = runTest {
        // Given: Accessibility service is enabled and our service is in the list
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } returns 1
        
        every {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } returns "com.siw.clipboardsync/com.siw.clipboardsync.service.ClipboardAccessibilityService"
        
        every { context.packageName } returns "com.siw.clipboardsync"
        
        // Mock static service instance
        mockkObject(ClipboardAccessibilityService.Companion)
        val mockService = mockk<ClipboardAccessibilityService>(relaxed = true)
        every { ClipboardAccessibilityService.getInstance() } returns mockService
        
        // When: Starting monitoring
        monitor.startMonitoring()
        
        // Then: Should be monitoring
        assertTrue(monitor.isMonitoring())
        verify { mockService.startClipboardMonitoring() }
    }
    
    @Test
    fun `stopMonitoring stops the service and sets monitoring to false`() = runTest {
        // Given: Monitor is running
        setupEnabledAccessibilityService()
        monitor.startMonitoring()
        assertTrue(monitor.isMonitoring())
        
        // When: Stopping monitoring
        monitor.stopMonitoring()
        
        // Then: Should not be monitoring
        assertFalse(monitor.isMonitoring())
    }
    
    @Test
    fun `createAccessibilitySettingsIntent returns correct intent`() {
        // When: Creating accessibility settings intent
        val intent = monitor.createAccessibilitySettingsIntent()
        
        // Then: Should have correct action and flags
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }
    
    @Test
    fun `getAccessibilityInstructions returns helpful instructions`() {
        // When: Getting instructions
        val instructions = monitor.getAccessibilityInstructions()
        
        // Then: Should contain helpful information
        assertTrue(instructions.contains("Settings"))
        assertTrue(instructions.contains("Accessibility"))
        assertTrue(instructions.contains("ClipboardSync"))
    }
    
    @Test
    fun `isServiceActiveAndMonitoring returns true when service is active and monitoring`() {
        // Given: Service is active and monitoring
        mockkObject(ClipboardAccessibilityService.Companion)
        val mockService = mockk<ClipboardAccessibilityService>()
        every { ClipboardAccessibilityService.getInstance() } returns mockService
        every { mockService.isMonitoring() } returns true
        
        // When: Checking if service is active and monitoring
        val result = monitor.isServiceActiveAndMonitoring()
        
        // Then: Should return true
        assertTrue(result)
    }
    
    @Test
    fun `isServiceActiveAndMonitoring returns false when service is not available`() {
        // Given: Service is not available
        mockkObject(ClipboardAccessibilityService.Companion)
        every { ClipboardAccessibilityService.getInstance() } returns null
        
        // When: Checking if service is active and monitoring
        val result = monitor.isServiceActiveAndMonitoring()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    @Test
    fun `isServiceActiveAndMonitoring returns false when service is available but not monitoring`() {
        // Given: Service is available but not monitoring
        mockkObject(ClipboardAccessibilityService.Companion)
        val mockService = mockk<ClipboardAccessibilityService>()
        every { ClipboardAccessibilityService.getInstance() } returns mockService
        every { mockService.isMonitoring() } returns false
        
        // When: Checking if service is active and monitoring
        val result = monitor.isServiceActiveAndMonitoring()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    private fun setupEnabledAccessibilityService() {
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } returns 1
        
        every {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } returns "com.siw.clipboardsync/com.siw.clipboardsync.service.ClipboardAccessibilityService"
        
        every { context.packageName } returns "com.siw.clipboardsync"
        
        mockkObject(ClipboardAccessibilityService.Companion)
        val mockService = mockk<ClipboardAccessibilityService>(relaxed = true)
        every { ClipboardAccessibilityService.getInstance() } returns mockService
    }
}