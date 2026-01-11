package com.siw.clipboardsync.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.siw.clipboardsync.service.ClipboardAccessibilityService
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for AccessibilityPermissionManager.
 * Note: The accessibility service is now only used for keep-alive, not clipboard monitoring.
 */
class AccessibilityPermissionManagerTest {
    
    private lateinit var context: Context
    private lateinit var permissionManager: AccessibilityPermissionManager
    
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        permissionManager = AccessibilityPermissionManager(context)
        
        // Mock Settings.Secure access
        mockkStatic(Settings.Secure::class)
        
        // Mock ClipboardAccessibilityService static methods
        mockkObject(ClipboardAccessibilityService.Companion)
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `isAccessibilityServiceEnabled returns false when accessibility is disabled system-wide`() {
        // Given: Accessibility is disabled system-wide
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } returns 0
        
        // When: Checking if service is enabled
        val result = permissionManager.isAccessibilityServiceEnabled()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    @Test
    fun `isAccessibilityServiceEnabled returns false when no services are enabled`() {
        // Given: Accessibility is enabled but no services are enabled
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
        } returns null
        
        // When: Checking if service is enabled
        val result = permissionManager.isAccessibilityServiceEnabled()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    @Test
    fun `isAccessibilityServiceEnabled returns false when our service is not in enabled list`() {
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
        
        // When: Checking if service is enabled
        val result = permissionManager.isAccessibilityServiceEnabled()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    @Test
    fun `isAccessibilityServiceEnabled returns true when our service is in enabled list`() {
        // Given: Accessibility is enabled and our service is in the list
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
        
        // When: Checking if service is enabled
        val result = permissionManager.isAccessibilityServiceEnabled()
        
        // Then: Should return true
        assertTrue(result)
    }
    
    @Test
    fun `isAccessibilityServiceEnabled handles Settings SettingNotFoundException`() {
        // Given: Settings.Secure throws SettingNotFoundException
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } throws Settings.SettingNotFoundException("Setting not found")
        
        // When: Checking if service is enabled
        val result = permissionManager.isAccessibilityServiceEnabled()
        
        // Then: Should return false and not crash
        assertFalse(result)
    }
    
    @Test
    fun `createAccessibilitySettingsIntent returns correct intent`() {
        // When: Creating accessibility settings intent
        val intent = permissionManager.createAccessibilitySettingsIntent()
        
        // Then: Should have correct action and flags
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }
    
    @Test
    fun `createServiceSpecificSettingsIntent returns service-specific intent`() {
        // Given: Package name is set
        every { context.packageName } returns "com.siw.clipboardsync"
        
        // When: Creating service-specific settings intent
        val intent = permissionManager.createServiceSpecificSettingsIntent()
        
        // Then: Should have correct action and component name
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW", intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertTrue(intent.hasExtra("component_name"))
    }
    
    @Test
    fun `getEnableInstructions returns helpful instructions`() {
        // When: Getting enable instructions
        val instructions = permissionManager.getEnableInstructions()
        
        // Then: Should contain helpful information
        assertTrue(instructions.contains("Settings"))
        assertTrue(instructions.contains("Accessibility"))
        assertTrue(instructions.contains("ClipboardSync"))
    }
    
    @Test
    fun `getPermissionRationale returns clear explanation`() {
        // When: Getting permission rationale
        val rationale = permissionManager.getPermissionRationale()
        
        // Then: Should contain clear explanation
        assertTrue(rationale.contains("accessibility service"))
        assertTrue(rationale.contains("clipboard"))
    }
    
    @Test
    fun `isServiceRunning delegates to ClipboardAccessibilityService`() {
        // Given: Service is running
        every { ClipboardAccessibilityService.isServiceRunning() } returns true
        
        // When: Checking if service is running
        val result = permissionManager.isServiceRunning()
        
        // Then: Should return true and verify delegation
        assertTrue(result)
        verify { ClipboardAccessibilityService.isServiceRunning() }
    }
    
    @Test
    fun `isServiceActiveAndRunning returns true when service is enabled and running`() {
        // Given: Service is enabled and running
        setupAccessibilityServiceEnabled()
        every { ClipboardAccessibilityService.isServiceRunning() } returns true
        
        // When: Checking if service is active and running
        val result = permissionManager.isServiceActiveAndRunning()
        
        // Then: Should return true
        assertTrue(result)
    }
    
    @Test
    fun `isServiceActiveAndRunning returns false when service is not enabled`() {
        // Given: Service is not enabled
        setupAccessibilityServiceNotEnabled()
        
        // When: Checking if service is active and running
        val result = permissionManager.isServiceActiveAndRunning()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    @Test
    fun `isServiceActiveAndRunning returns false when service is enabled but not running`() {
        // Given: Service is enabled but not running
        setupAccessibilityServiceEnabled()
        every { ClipboardAccessibilityService.isServiceRunning() } returns false
        
        // When: Checking if service is active and running
        val result = permissionManager.isServiceActiveAndRunning()
        
        // Then: Should return false
        assertFalse(result)
    }
    
    @Test
    fun `getServiceStatus returns NOT_ENABLED when service is not enabled`() {
        // Given: Service is not enabled
        setupAccessibilityServiceNotEnabled()
        
        // When: Getting service status
        val status = permissionManager.getServiceStatus()
        
        // Then: Should return NOT_ENABLED
        assertEquals(AccessibilityPermissionManager.AccessibilityServiceStatus.NOT_ENABLED, status)
    }
    
    @Test
    fun `getServiceStatus returns ENABLED_BUT_NOT_RUNNING when service is enabled but not running`() {
        // Given: Service is enabled but not running
        setupAccessibilityServiceEnabled()
        every { ClipboardAccessibilityService.isServiceRunning() } returns false
        
        // When: Getting service status
        val status = permissionManager.getServiceStatus()
        
        // Then: Should return ENABLED_BUT_NOT_RUNNING
        assertEquals(AccessibilityPermissionManager.AccessibilityServiceStatus.ENABLED_BUT_NOT_RUNNING, status)
    }
    
    @Test
    fun `getServiceStatus returns ACTIVE_FOR_KEEP_ALIVE when service is running`() {
        // Given: Service is enabled and running
        setupAccessibilityServiceEnabled()
        every { ClipboardAccessibilityService.isServiceRunning() } returns true
        
        // When: Getting service status
        val status = permissionManager.getServiceStatus()
        
        // Then: Should return ACTIVE_FOR_KEEP_ALIVE
        assertEquals(AccessibilityPermissionManager.AccessibilityServiceStatus.ACTIVE_FOR_KEEP_ALIVE, status)
    }
    
    @Test
    fun `requestAccessibilityPermission calls callback with true when already enabled`() {
        // Given: Service is already enabled
        setupAccessibilityServiceEnabled()
        
        var callbackResult: Boolean? = null
        val callback: (Boolean) -> Unit = { result ->
            callbackResult = result
        }
        
        // When: Requesting accessibility permission
        permissionManager.requestAccessibilityPermission(callback)
        
        // Then: Should call callback with true
        assertEquals(true, callbackResult)
    }
    
    @Test
    fun `requestAccessibilityPermission opens settings when not enabled`() {
        // Given: Service is not enabled
        setupAccessibilityServiceNotEnabled()
        every { context.packageName } returns "com.siw.clipboardsync"
        
        var callbackCalled = false
        val callback: (Boolean) -> Unit = { _ ->
            callbackCalled = true
        }
        
        // When: Requesting accessibility permission
        permissionManager.requestAccessibilityPermission(callback)
        
        // Then: Should start activity to open settings
        verify { context.startActivity(any()) }
        // Callback should not be called immediately since user needs to enable service
        assertFalse(callbackCalled)
    }
    
    private fun setupAccessibilityServiceEnabled() {
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
    }
    
    private fun setupAccessibilityServiceNotEnabled() {
        every { 
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } returns 0
    }
}
