package com.siw.clipboardsync.monitor

import android.content.Context
import com.siw.clipboardsync.monitor.model.ClipboardContent
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for XposedHookManager.
 * Note: These tests mock Xposed framework interactions since actual Xposed testing
 * requires framework installation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XposedHookManagerTest {
    
    private lateinit var context: Context
    private lateinit var xposedHookManager: XposedHookManager
    
    @Before
    fun setUp() {
        context = mockk()
        xposedHookManager = XposedHookManager(context)
    }
    
    @After
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `isAvailable should return false when no Xposed framework`() {
        // When
        val result = xposedHookManager.isAvailable()
        
        // Then
        // Since Xposed framework is not available in test environment, this should return false
        assertFalse(result)
    }
    
    @Test
    fun `hookClipboardService should throw exception when not available`() {
        // Given
        val callback: (ClipboardContent) -> Unit = mockk()
        
        // When & Then
        try {
            xposedHookManager.hookClipboardService(callback)
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            assertEquals("XPOSED_FRAMEWORK_ERROR", e.errorCode)
        }
    }
    
    @Test
    fun `unhookClipboardService should not throw when not hooked`() {
        // When & Then - should not throw
        xposedHookManager.unhookClipboardService()
    }
    
    @Test
    fun `getXposedCapabilities should return unavailable status`() {
        // When
        val capabilities = xposedHookManager.getXposedCapabilities()
        
        // Then
        assertEquals(false, capabilities["available"])
        assertEquals("NONE", capabilities["frameworkType"])
        assertEquals(false, capabilities["isHooked"])
        assertTrue(capabilities.containsKey("supportedMethods"))
    }
    
    @Test
    fun `isRunningInXposedEnvironment should return false in test`() {
        // When
        val result = xposedHookManager.isRunningInXposedEnvironment()
        
        // Then
        assertFalse(result)
    }
    
    @Test
    fun `detectXposedFramework should return NONE in test environment`() {
        // Given
        val detectXposedFrameworkMethod = XposedHookManager::class.java.getDeclaredMethod(
            "detectXposedFramework"
        )
        detectXposedFrameworkMethod.isAccessible = true
        
        // When
        val result = detectXposedFrameworkMethod.invoke(xposedHookManager) as XposedHookManager.XposedFrameworkType
        
        // Then
        assertEquals(XposedHookManager.XposedFrameworkType.NONE, result)
    }
    
    @Test
    fun `extractClipDataFromParam should return mock content`() {
        // Given
        val extractClipDataFromParamMethod = XposedHookManager::class.java.getDeclaredMethod(
            "extractClipDataFromParam",
            Any::class.java
        )
        extractClipDataFromParamMethod.isAccessible = true
        
        // When
        val result = extractClipDataFromParamMethod.invoke(xposedHookManager, "test param") as ClipboardContent?
        
        // Then
        assertNotNull(result)
        assertEquals(ClipboardContent.ContentType.TEXT, result!!.type)
        assertEquals("text/plain", result.mimeType)
        assertEquals("xposed_hook", result.source)
        assertArrayEquals("Mock clipboard content".toByteArray(), result.data)
    }
    
    @Test
    fun `createMethodHook should return valid hook object`() {
        // Given
        val createMethodHookMethod = XposedHookManager::class.java.getDeclaredMethod(
            "createMethodHook"
        )
        createMethodHookMethod.isAccessible = true
        
        // When
        val result = createMethodHookMethod.invoke(xposedHookManager)
        
        // Then
        assertNotNull(result)
        // The hook object should have the expected structure (mock implementation)
    }
    
    @Test
    fun `simulateClipboardHook should not throw`() {
        // Given
        val simulateClipboardHookMethod = XposedHookManager::class.java.getDeclaredMethod(
            "simulateClipboardHook"
        )
        simulateClipboardHookMethod.isAccessible = true
        
        // When & Then - should not throw
        simulateClipboardHookMethod.invoke(xposedHookManager)
    }
    
    @Test
    fun `hookWithXposed should throw exception in test environment`() {
        // Given
        val hookWithXposedMethod = XposedHookManager::class.java.getDeclaredMethod(
            "hookWithXposed"
        )
        hookWithXposedMethod.isAccessible = true
        
        // When & Then
        try {
            hookWithXposedMethod.invoke(xposedHookManager)
            fail("Expected exception")
        } catch (e: Exception) {
            // Should throw because Xposed classes are not available
            assertTrue(e.cause is ClipboardMonitorException)
        }
    }
    
    @Test
    fun `hookWithLSPosed should not throw in test environment`() {
        // Given
        val hookWithLSPosedMethod = XposedHookManager::class.java.getDeclaredMethod(
            "hookWithLSPosed"
        )
        hookWithLSPosedMethod.isAccessible = true
        
        // When & Then
        try {
            hookWithLSPosedMethod.invoke(xposedHookManager)
            fail("Expected exception")
        } catch (e: Exception) {
            // Should throw because LSPosed classes are not available
            assertTrue(e.cause is ClipboardMonitorException)
        }
    }
    
    @Test
    fun `unhookFromXposed should not throw`() {
        // Given
        val unhookFromXposedMethod = XposedHookManager::class.java.getDeclaredMethod(
            "unhookFromXposed"
        )
        unhookFromXposedMethod.isAccessible = true
        
        // When & Then - should not throw
        unhookFromXposedMethod.invoke(xposedHookManager)
    }
    
    @Test
    fun `unhookFromLSPosed should not throw`() {
        // Given
        val unhookFromLSPosedMethod = XposedHookManager::class.java.getDeclaredMethod(
            "unhookFromLSPosed"
        )
        unhookFromLSPosedMethod.isAccessible = true
        
        // When & Then - should not throw
        unhookFromLSPosedMethod.invoke(xposedHookManager)
    }
}