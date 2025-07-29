package com.siw.clipboardsync.monitor

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/**
 * Unit tests for NativeHookManager integration with NativeClipboardHook
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeHookManagerTest {

    private lateinit var nativeHookManager: NativeHookManager
    private val mockNativeHook = mockk<NativeClipboardHook>(relaxed = true)

    @Before
    fun setUp() {
        // Mock the native hook creation
        mockkConstructor(NativeClipboardHook::class)
        every { anyConstructed<NativeClipboardHook>().isNativeLibraryAvailable() } returns true
        every { anyConstructed<NativeClipboardHook>().initializeHooks() } returns true
        every { anyConstructed<NativeClipboardHook>().startMonitoring() } returns true
        every { anyConstructed<NativeClipboardHook>().stopMonitoring() } returns true
        every { anyConstructed<NativeClipboardHook>().isMonitoringActive() } returns false
        
        nativeHookManager = NativeHookManager()
    }

    @After
    fun tearDown() {
        nativeHookManager.cleanup()
        unmockkAll()
    }

    @Test
    fun `isAvailable returns true when native library is available`() {
        // Given: Native library is available
        every { anyConstructed<NativeClipboardHook>().isNativeLibraryAvailable() } returns true
        
        // When: Checking availability
        val result = nativeHookManager.isAvailable()
        
        // Then: Should return true
        assertTrue(result)
    }

    @Test
    fun `isAvailable returns false when native library is not available`() {
        // Given: Native library is not available
        every { anyConstructed<NativeClipboardHook>().isNativeLibraryAvailable() } returns false
        
        // When: Checking availability
        val result = nativeHookManager.isAvailable()
        
        // Then: Should return false
        assertFalse(result)
    }

    @Test
    fun `initialize returns true when native hooks initialize successfully`() = runTest {
        // Given: Native hooks can be initialized
        every { anyConstructed<NativeClipboardHook>().initializeHooks() } returns true
        
        // When: Initializing
        val result = nativeHookManager.initialize()
        
        // Then: Should return true
        assertTrue(result)
        verify { anyConstructed<NativeClipboardHook>().initializeHooks() }
    }

    @Test
    fun `initialize returns false when native hooks fail to initialize`() = runTest {
        // Given: Native hooks fail to initialize
        every { anyConstructed<NativeClipboardHook>().initializeHooks() } returns false
        
        // When: Initializing
        val result = nativeHookManager.initialize()
        
        // Then: Should return false
        assertFalse(result)
        verify { anyConstructed<NativeClipboardHook>().initializeHooks() }
    }

    @Test
    fun `registerClipboardCallback registers callback with native hook`() = runTest {
        // Given: Initialized manager
        nativeHookManager.initialize()
        val callback: (String) -> Unit = { }
        
        // When: Registering callback
        nativeHookManager.registerClipboardCallback(callback)
        
        // Then: Should register with native hook
        verify { anyConstructed<NativeClipboardHook>().registerClipboardCallback(any()) }
    }

    @Test
    fun `startMonitoring returns true when native monitoring starts successfully`() = runTest {
        // Given: Initialized manager
        nativeHookManager.initialize()
        every { anyConstructed<NativeClipboardHook>().startMonitoring() } returns true
        
        // When: Starting monitoring
        val result = nativeHookManager.startMonitoring()
        
        // Then: Should return true
        assertTrue(result)
        verify { anyConstructed<NativeClipboardHook>().startMonitoring() }
    }

    @Test
    fun `startMonitoring returns false when native monitoring fails to start`() = runTest {
        // Given: Initialized manager
        nativeHookManager.initialize()
        every { anyConstructed<NativeClipboardHook>().startMonitoring() } returns false
        
        // When: Starting monitoring
        val result = nativeHookManager.startMonitoring()
        
        // Then: Should return false
        assertFalse(result)
        verify { anyConstructed<NativeClipboardHook>().startMonitoring() }
    }

    @Test
    fun `stopMonitoring returns true when native monitoring stops successfully`() = runTest {
        // Given: Monitoring is active
        nativeHookManager.initialize()
        nativeHookManager.startMonitoring()
        every { anyConstructed<NativeClipboardHook>().stopMonitoring() } returns true
        
        // When: Stopping monitoring
        val result = nativeHookManager.stopMonitoring()
        
        // Then: Should return true
        assertTrue(result)
        verify { anyConstructed<NativeClipboardHook>().stopMonitoring() }
    }

    @Test
    fun `isMonitoring returns correct status from native hook`() = runTest {
        // Given: Native hook monitoring status
        every { anyConstructed<NativeClipboardHook>().isMonitoringActive() } returns true
        
        // When: Checking monitoring status
        val result = nativeHookManager.isMonitoring()
        
        // Then: Should return native hook status
        assertTrue(result)
        verify { anyConstructed<NativeClipboardHook>().isMonitoringActive() }
    }

    @Test
    fun `getCurrentClipboardContent returns content from native hook`() = runTest {
        // Given: Native hook returns content
        val expectedContent = "test clipboard content"
        every { anyConstructed<NativeClipboardHook>().getCurrentClipboardContent() } returns expectedContent
        
        // When: Getting clipboard content
        val result = nativeHookManager.getCurrentClipboardContent()
        
        // Then: Should return content from native hook
        assertEquals(expectedContent, result)
        verify { anyConstructed<NativeClipboardHook>().getCurrentClipboardContent() }
    }

    @Test
    fun `getCurrentClipboardContent returns null when native hook returns null`() = runTest {
        // Given: Native hook returns null
        every { anyConstructed<NativeClipboardHook>().getCurrentClipboardContent() } returns null
        
        // When: Getting clipboard content
        val result = nativeHookManager.getCurrentClipboardContent()
        
        // Then: Should return null
        assertNull(result)
        verify { anyConstructed<NativeClipboardHook>().getCurrentClipboardContent() }
    }

    @Test
    fun `setClipboardContent returns true when native hook succeeds`() = runTest {
        // Given: Native hook succeeds
        val testContent = "test content to set"
        every { anyConstructed<NativeClipboardHook>().setClipboardContent(testContent) } returns true
        
        // When: Setting clipboard content
        val result = nativeHookManager.setClipboardContent(testContent)
        
        // Then: Should return true
        assertTrue(result)
        verify { anyConstructed<NativeClipboardHook>().setClipboardContent(testContent) }
    }

    @Test
    fun `setClipboardContent returns false when native hook fails`() = runTest {
        // Given: Native hook fails
        val testContent = "test content to set"
        every { anyConstructed<NativeClipboardHook>().setClipboardContent(testContent) } returns false
        
        // When: Setting clipboard content
        val result = nativeHookManager.setClipboardContent(testContent)
        
        // Then: Should return false
        assertFalse(result)
        verify { anyConstructed<NativeClipboardHook>().setClipboardContent(testContent) }
    }

    @Test
    fun `cleanup calls native hook cleanup`() = runTest {
        // Given: Initialized manager
        nativeHookManager.initialize()
        
        // When: Cleaning up
        nativeHookManager.cleanup()
        
        // Then: Should call native hook cleanup
        verify { anyConstructed<NativeClipboardHook>().cleanup() }
    }

    @Test
    fun `multiple cleanup calls are safe`() = runTest {
        // Given: Initialized manager
        nativeHookManager.initialize()
        
        // When: Calling cleanup multiple times
        nativeHookManager.cleanup()
        nativeHookManager.cleanup()
        nativeHookManager.cleanup()
        
        // Then: Should handle multiple calls gracefully
        verify(atLeast = 1) { anyConstructed<NativeClipboardHook>().cleanup() }
    }

    @Test
    fun `operations fail gracefully when not initialized`() = runTest {
        // Given: Manager not initialized
        
        // When/Then: Operations should handle uninitialized state
        assertFalse(nativeHookManager.startMonitoring())
        assertFalse(nativeHookManager.stopMonitoring())
        assertFalse(nativeHookManager.isMonitoring())
        assertNull(nativeHookManager.getCurrentClipboardContent())
        assertFalse(nativeHookManager.setClipboardContent("test"))
    }

    @Test
    fun `callback wrapper handles exceptions gracefully`() = runTest {
        // Given: Callback that throws exception
        val faultyCallback: (String) -> Unit = { throw RuntimeException("Test exception") }
        nativeHookManager.initialize()
        
        // When: Registering faulty callback
        nativeHookManager.registerClipboardCallback(faultyCallback)
        
        // Then: Should not crash during registration
        verify { anyConstructed<NativeClipboardHook>().registerClipboardCallback(any()) }
    }

    @Test
    fun `state consistency maintained across operations`() = runTest {
        // Given: Fresh manager
        assertFalse(nativeHookManager.isAvailable() && nativeHookManager.isMonitoring())
        
        // When: Performing sequence of operations
        assertTrue(nativeHookManager.initialize())
        nativeHookManager.registerClipboardCallback { }
        assertTrue(nativeHookManager.startMonitoring())
        
        // Simulate monitoring active
        every { anyConstructed<NativeClipboardHook>().isMonitoringActive() } returns true
        assertTrue(nativeHookManager.isMonitoring())
        
        assertTrue(nativeHookManager.stopMonitoring())
        every { anyConstructed<NativeClipboardHook>().isMonitoringActive() } returns false
        assertFalse(nativeHookManager.isMonitoring())
        
        // Then: State should be consistent
        nativeHookManager.cleanup()
    }
}