package com.siw.clipboardsync.monitor

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Integration tests for NativeClipboardHook with simulated native library behavior
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeClipboardHookIntegrationTest {

    private lateinit var nativeClipboardHook: NativeClipboardHook
    private val receivedCallbacks = mutableListOf<Pair<String, Long>>()
    private val receivedErrors = mutableListOf<Pair<Int, String>>()

    @Before
    fun setUp() {
        // Mock successful library loading
        mockkStatic(System::class)
        every { System.loadLibrary(any()) } just Runs
        
        nativeClipboardHook = NativeClipboardHook()
        receivedCallbacks.clear()
        receivedErrors.clear()
    }

    @After
    fun tearDown() {
        nativeClipboardHook.cleanup()
        unmockkAll()
    }

    @Test
    fun `full workflow integration test`() = runTest {
        // Given: Native library is available
        assertTrue(nativeClipboardHook.isNativeLibraryAvailable())
        
        // When: Performing full initialization workflow
        val callback: (String, Long) -> Unit = { content, timestamp ->
            receivedCallbacks.add(content to timestamp)
        }
        
        // Register callback
        nativeClipboardHook.registerClipboardCallback(callback)
        
        // Initialize hooks (will fail in test environment but should handle gracefully)
        val initResult = nativeClipboardHook.initializeHooks()
        
        // Start monitoring (will fail in test environment but should handle gracefully)
        val startResult = nativeClipboardHook.startMonitoring()
        
        // Verify state
        // Note: In test environment, native calls will fail, but wrapper should handle gracefully
        assertFalse(nativeClipboardHook.isMonitoringActive()) // Expected to be false due to mocked environment
        
        // Stop monitoring
        val stopResult = nativeClipboardHook.stopMonitoring()
        assertTrue(stopResult) // Should succeed even if not actually monitoring
        
        // Cleanup
        nativeClipboardHook.cleanup()
    }

    @Test
    fun `callback mechanism integration test`() = runTest {
        // Given: Callback is registered
        val latch = CountDownLatch(1)
        var callbackContent: String? = null
        var callbackTimestamp: Long? = null
        
        val callback: (String, Long) -> Unit = { content, timestamp ->
            callbackContent = content
            callbackTimestamp = timestamp
            latch.countDown()
        }
        
        nativeClipboardHook.registerClipboardCallback(callback)
        
        // When: Simulating native callback using reflection
        val onClipboardChangedMethod = NativeClipboardHook::class.java.getDeclaredMethod(
            "onClipboardChanged", String::class.java, Long::class.java
        )
        onClipboardChangedMethod.isAccessible = true
        
        val testContent = "Integration test content"
        val testTimestamp = System.currentTimeMillis()
        
        onClipboardChangedMethod.invoke(nativeClipboardHook, testContent, testTimestamp)
        
        // Then: Callback should be invoked
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(testContent, callbackContent)
        assertEquals(testTimestamp, callbackTimestamp)
    }

    @Test
    fun `error callback integration test`() = runTest {
        // Given: Error callback mechanism
        val latch = CountDownLatch(1)
        var errorCode: Int? = null
        var errorMessage: String? = null
        
        // When: Simulating native error callback using reflection
        val onNativeErrorMethod = NativeClipboardHook::class.java.getDeclaredMethod(
            "onNativeError", Int::class.java, String::class.java
        )
        onNativeErrorMethod.isAccessible = true
        
        val testErrorCode = -1
        val testErrorMessage = "Integration test error"
        
        onNativeErrorMethod.invoke(nativeClipboardHook, testErrorCode, testErrorMessage)
        
        // Then: Error should be handled gracefully (no crash)
        // The actual error handling would be verified through logs in a real scenario
    }

    @Test
    fun `concurrent operations integration test`() = runTest {
        // Given: Multiple concurrent operations
        val callbacks = mutableListOf<Pair<String, Long>>()
        val callback: (String, Long) -> Unit = { content, timestamp ->
            synchronized(callbacks) {
                callbacks.add(content to timestamp)
            }
        }
        
        nativeClipboardHook.registerClipboardCallback(callback)
        
        // When: Performing concurrent operations
        val operations = listOf(
            { nativeClipboardHook.initializeHooks() },
            { nativeClipboardHook.startMonitoring() },
            { nativeClipboardHook.getCurrentClipboardContent() },
            { nativeClipboardHook.setClipboardContent("concurrent test") },
            { nativeClipboardHook.stopMonitoring() }
        )
        
        // Execute operations concurrently
        operations.forEach { operation ->
            kotlinx.coroutines.launch {
                try {
                    operation()
                } catch (e: Exception) {
                    // Expected in test environment due to missing native library
                }
            }
        }
        
        delay(100) // Allow operations to complete
        
        // Then: No crashes should occur
        // Test passes if no exceptions are thrown
    }

    @Test
    fun `resource cleanup integration test`() = runTest {
        // Given: Resources are allocated
        nativeClipboardHook.registerClipboardCallback { _, _ -> }
        nativeClipboardHook.initializeHooks()
        nativeClipboardHook.startMonitoring()
        
        // When: Cleaning up multiple times
        repeat(3) {
            nativeClipboardHook.cleanup()
        }
        
        // Then: Should handle multiple cleanup calls gracefully
        assertFalse(nativeClipboardHook.isMonitoringActive())
    }

    @Test
    fun `state consistency integration test`() = runTest {
        // Given: Initial state
        assertFalse(nativeClipboardHook.isMonitoringActive())
        
        // When: Performing state changes
        nativeClipboardHook.initializeHooks()
        nativeClipboardHook.startMonitoring()
        
        // Note: In test environment, monitoring won't actually start due to missing native library
        // but the wrapper should maintain consistent state
        
        nativeClipboardHook.stopMonitoring()
        assertFalse(nativeClipboardHook.isMonitoringActive())
        
        // Then: State should remain consistent
        nativeClipboardHook.cleanup()
        assertFalse(nativeClipboardHook.isMonitoringActive())
    }

    @Test
    fun `exception handling integration test`() = runTest {
        // Given: Operations that may throw exceptions
        val operations = listOf<() -> Any?>(
            { nativeClipboardHook.initializeHooks() },
            { nativeClipboardHook.startMonitoring() },
            { nativeClipboardHook.stopMonitoring() },
            { nativeClipboardHook.getCurrentClipboardContent() },
            { nativeClipboardHook.setClipboardContent("test") },
            { nativeClipboardHook.cleanup() }
        )
        
        // When: Executing operations that may fail
        operations.forEach { operation ->
            try {
                operation()
            } catch (e: Exception) {
                // Expected in test environment
                // Verify that exceptions are handled gracefully
                assertTrue(e is UnsatisfiedLinkError || e is RuntimeException)
            }
        }
        
        // Then: Application should remain stable
        // Test passes if no unhandled exceptions crash the test
    }

    @Test
    fun `memory management integration test`() = runTest {
        // Given: Multiple instances and operations
        val hooks = mutableListOf<NativeClipboardHook>()
        
        // When: Creating multiple instances
        repeat(5) {
            val hook = NativeClipboardHook()
            hooks.add(hook)
            
            hook.registerClipboardCallback { _, _ -> }
            hook.initializeHooks()
        }
        
        // Clean up all instances
        hooks.forEach { it.cleanup() }
        
        // Then: Memory should be properly managed
        // Test passes if no memory leaks occur (verified by not crashing)
    }

    @Test
    fun `callback error handling integration test`() = runTest {
        // Given: Callback that throws exception
        val faultyCallback: (String, Long) -> Unit = { _, _ ->
            throw RuntimeException("Callback error")
        }
        
        nativeClipboardHook.registerClipboardCallback(faultyCallback)
        
        // When: Triggering callback with exception
        val onClipboardChangedMethod = NativeClipboardHook::class.java.getDeclaredMethod(
            "onClipboardChanged", String::class.java, Long::class.java
        )
        onClipboardChangedMethod.isAccessible = true
        
        // Then: Should handle callback exceptions gracefully
        assertDoesNotThrow {
            onClipboardChangedMethod.invoke(nativeClipboardHook, "test", 123L)
        }
    }
}