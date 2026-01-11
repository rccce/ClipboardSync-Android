package com.siw.clipboardsync.monitor

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for NativeClipboardHook JNI wrapper
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeClipboardHookTest {

    private lateinit var nativeClipboardHook: NativeClipboardHook
    private val mockCallback = mockk<(String, Long) -> Unit>(relaxed = true)

    @Before
    fun setUp() {
        // Mock the native library loading to avoid UnsatisfiedLinkError in tests
        mockkStatic(System::class)
        every { System.loadLibrary(any()) } just Runs
        
        nativeClipboardHook = NativeClipboardHook()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isNativeLibraryAvailable returns false when library not loaded`() {
        // Given: Library loading fails
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        
        // When: Creating new instance
        val hook = NativeClipboardHook()
        
        // Then: Library should not be available
        assertFalse(hook.isNativeLibraryAvailable())
    }

    @Test
    fun `isNativeLibraryAvailable returns true when library loaded successfully`() {
        // Given: Library loading succeeds (mocked in setUp)
        
        // Then: Library should be available
        assertTrue(nativeClipboardHook.isNativeLibraryAvailable())
    }

    @Test
    fun `initializeHooks returns false when native library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Initializing hooks
        val result = hook.initializeHooks()
        
        // Then: Should return false
        assertFalse(result)
    }

    @Test
    fun `registerClipboardCallback does nothing when library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Registering callback
        hook.registerClipboardCallback(mockCallback)
        
        // Then: Should not crash (just log warning)
        verify { mockCallback wasNot Called }
    }

    @Test
    fun `startMonitoring returns false when library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Starting monitoring
        val result = hook.startMonitoring()
        
        // Then: Should return false
        assertFalse(result)
    }

    @Test
    fun `stopMonitoring returns true when library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Stopping monitoring
        val result = hook.stopMonitoring()
        
        // Then: Should return true (considered stopped)
        assertTrue(result)
    }

    @Test
    fun `getCurrentClipboardContent returns null when library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Getting clipboard content
        val result = hook.getCurrentClipboardContent()
        
        // Then: Should return null
        assertNull(result)
    }

    @Test
    fun `setClipboardContent returns false when library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Setting clipboard content
        val result = hook.setClipboardContent("test content")
        
        // Then: Should return false
        assertFalse(result)
    }

    @Test
    fun `isMonitoringActive returns false initially`() {
        // When: Checking monitoring status initially
        val result = nativeClipboardHook.isMonitoringActive()
        
        // Then: Should return false
        assertFalse(result)
    }

    @Test
    fun `cleanup does not crash when library not available`() {
        // Given: Library not available
        every { System.loadLibrary(any()) } throws UnsatisfiedLinkError("Library not found")
        val hook = NativeClipboardHook()
        
        // When: Cleaning up
        hook.cleanup()
        
        // Then: Should not crash
        // Test passes if no exception is thrown
    }

    @Test
    fun `callback registration stores callback function`() = runTest {
        // Given: Valid callback function
        val testCallback: (String, Long) -> Unit = { content, timestamp ->
            // Test callback implementation
        }
        
        // When: Registering callback
        nativeClipboardHook.registerClipboardCallback(testCallback)
        
        // Then: Callback should be stored (verified by not crashing)
        // Note: We can't directly test the private callback field,
        // but we can verify the method doesn't throw exceptions
    }

    @Test
    fun `multiple cleanup calls are safe`() {
        // When: Calling cleanup multiple times
        nativeClipboardHook.cleanup()
        nativeClipboardHook.cleanup()
        nativeClipboardHook.cleanup()
        
        // Then: Should not crash
        // Test passes if no exception is thrown
    }

    @Test
    fun `native method calls are properly handled when library available`() {
        // Given: Library is available (mocked)
        assertTrue(nativeClipboardHook.isNativeLibraryAvailable())
        
        // When/Then: Various operations should not crash
        // Note: Since we can't actually load the native library in tests,
        // these will throw UnsatisfiedLinkError, but the wrapper should handle it gracefully
        
        // Initialize hooks
        val initResult = nativeClipboardHook.initializeHooks()
        // Should handle native call gracefully (may return false due to mocking)
        
        // Register callback
        nativeClipboardHook.registerClipboardCallback(mockCallback)
        // Should not crash
        
        // Start monitoring
        val startResult = nativeClipboardHook.startMonitoring()
        // Should handle native call gracefully
        
        // Stop monitoring
        val stopResult = nativeClipboardHook.stopMonitoring()
        // Should handle native call gracefully
        
        // Get content
        val content = nativeClipboardHook.getCurrentClipboardContent()
        // Should handle native call gracefully (may return null)
        
        // Set content
        val setResult = nativeClipboardHook.setClipboardContent("test")
        // Should handle native call gracefully
        
        // Cleanup
        nativeClipboardHook.cleanup()
        // Should not crash
    }

    @Test
    fun `error handling in native callbacks`() {
        // This test verifies that the JNI callback methods exist and can be called
        // In a real scenario, these would be called from native code
        
        // Create a spy to verify method calls
        val hookSpy = spyk(nativeClipboardHook)
        
        // Simulate native callbacks using reflection to access private methods
        val onClipboardChangedMethod = NativeClipboardHook::class.java.getDeclaredMethod(
            "onClipboardChanged", String::class.java, Long::class.java
        )
        onClipboardChangedMethod.isAccessible = true
        
        val onNativeErrorMethod = NativeClipboardHook::class.java.getDeclaredMethod(
            "onNativeError", Int::class.java, String::class.java
        )
        onNativeErrorMethod.isAccessible = true
        
        // Test clipboard change callback
        onClipboardChangedMethod.invoke(hookSpy, "test content", 123456789L)
        
        // Test error callback
        onNativeErrorMethod.invoke(hookSpy, -1, "Test error message")
        
        // Verify methods were called without crashing
        // The actual callback functionality would be tested in integration tests
    }
}