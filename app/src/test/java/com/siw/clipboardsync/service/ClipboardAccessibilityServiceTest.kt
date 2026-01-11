package com.siw.clipboardsync.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.siw.clipboardsync.monitor.model.ClipboardContent
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [Build.VERSION_CODES.Q]) // Android 10
class ClipboardAccessibilityServiceTest {
    
    private lateinit var service: ClipboardAccessibilityService
    private lateinit var mockClipboardManager: ClipboardManager
    private var clipboardChangedCallback: ((ClipboardContent, Long) -> Unit)? = null
    private var errorCallback: ((Throwable) -> Unit)? = null
    
    @Before
    fun setUp() {
        service = spyk(ClipboardAccessibilityService())
        mockClipboardManager = mockk(relaxed = true)
        
        // Mock getSystemService to return our mock clipboard manager
        every { 
            service.getSystemService(Context.CLIPBOARD_SERVICE) 
        } returns mockClipboardManager
        
        // Capture callbacks
        service.setOnClipboardChanged { content, timestamp ->
            clipboardChangedCallback?.invoke(content, timestamp)
        }
        
        service.setOnError { throwable ->
            errorCallback?.invoke(throwable)
        }
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `getInstance returns null initially`() {
        assertNull(ClipboardAccessibilityService.getInstance())
    }
    
    @Test
    fun `isServiceRunning returns false initially`() {
        assertFalse(ClipboardAccessibilityService.isServiceRunning())
    }
    
    @Test
    fun `onCreate sets instance and initializes clipboard manager`() {
        // When: onCreate is called
        service.onCreate()
        
        // Then: Instance should be set
        assertEquals(service, ClipboardAccessibilityService.getInstance())
        assertTrue(ClipboardAccessibilityService.isServiceRunning())
    }
    
    @Test
    fun `onDestroy clears instance and stops monitoring`() {
        // Given: Service is created and monitoring
        service.onCreate()
        service.startClipboardMonitoring()
        assertTrue(service.isMonitoring())
        
        // When: onDestroy is called
        service.onDestroy()
        
        // Then: Instance should be cleared and monitoring stopped
        assertNull(ClipboardAccessibilityService.getInstance())
        assertFalse(ClipboardAccessibilityService.isServiceRunning())
        assertFalse(service.isMonitoring())
    }
    
    @Test
    fun `startClipboardMonitoring adds listener and starts monitoring`() {
        // Given: Service is created
        service.onCreate()
        
        // When: Starting clipboard monitoring
        service.startClipboardMonitoring()
        
        // Then: Should be monitoring and listener should be added
        assertTrue(service.isMonitoring())
        verify { mockClipboardManager.addPrimaryClipChangedListener(any()) }
    }
    
    @Test
    fun `stopClipboardMonitoring removes listener and stops monitoring`() {
        // Given: Service is monitoring
        service.onCreate()
        service.startClipboardMonitoring()
        assertTrue(service.isMonitoring())
        
        // When: Stopping clipboard monitoring
        service.stopClipboardMonitoring()
        
        // Then: Should not be monitoring and listener should be removed
        assertFalse(service.isMonitoring())
        verify { mockClipboardManager.removePrimaryClipChangedListener(any()) }
    }
    
    @Test
    fun `extractClipboardContent handles text content correctly`() = runTest {
        // Given: ClipData.Item with text content
        val testText = "Hello, World!"
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.text } returns testText
        every { clipItem.htmlText } returns null
        every { clipItem.uri } returns null
        
        // When: Extracting clipboard content
        val content = service.invokePrivate("extractClipboardContent", clipItem, System.currentTimeMillis())
        
        // Then: Should create correct ClipboardContent
        assertEquals(ClipboardContent.ContentType.TEXT, content.type)
        assertEquals(testText, String(content.data))
        assertEquals("text/plain", content.mimeType)
        assertEquals("accessibility_service", content.source)
        assertEquals(testText.length.toLong(), content.size)
        assertTrue(content.metadata.containsKey("method"))
        assertTrue(content.metadata.containsKey("android_version"))
    }
    
    @Test
    fun `extractClipboardContent handles HTML content correctly`() = runTest {
        // Given: ClipData.Item with HTML content
        val testHtml = "<p>Hello, <b>World!</b></p>"
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.text } returns null
        every { clipItem.htmlText } returns testHtml
        every { clipItem.uri } returns null
        
        // When: Extracting clipboard content
        val content = service.invokePrivate("extractClipboardContent", clipItem, System.currentTimeMillis())
        
        // Then: Should create correct ClipboardContent
        assertEquals(ClipboardContent.ContentType.HTML, content.type)
        assertEquals(testHtml, String(content.data))
        assertEquals("text/html", content.mimeType)
        assertEquals("accessibility_service", content.source)
        assertEquals(testHtml.length.toLong(), content.size)
    }
    
    @Test
    fun `extractClipboardContent handles URI content correctly`() = runTest {
        // Given: ClipData.Item with URI content
        val testUri = Uri.parse("https://example.com/test")
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.text } returns null
        every { clipItem.htmlText } returns null
        every { clipItem.uri } returns testUri
        
        // When: Extracting clipboard content
        val content = service.invokePrivate("extractClipboardContent", clipItem, System.currentTimeMillis())
        
        // Then: Should create correct ClipboardContent
        assertEquals(ClipboardContent.ContentType.URI, content.type)
        assertEquals(testUri.toString(), String(content.data))
        assertEquals("text/uri-list", content.mimeType)
        assertEquals("accessibility_service", content.source)
        assertEquals(testUri.toString().length.toLong(), content.size)
        assertTrue(content.metadata.containsKey("uri_scheme"))
        assertEquals("https", content.metadata["uri_scheme"])
    }
    
    @Test
    fun `extractClipboardContent handles unknown content with fallback`() = runTest {
        // Given: ClipData.Item with unknown content
        val fallbackText = "Fallback text"
        val clipItem = mockk<ClipData.Item>()
        every { clipItem.text } returns null
        every { clipItem.htmlText } returns null
        every { clipItem.uri } returns null
        every { clipItem.coerceToText(service) } returns fallbackText
        
        // When: Extracting clipboard content
        val content = service.invokePrivate("extractClipboardContent", clipItem, System.currentTimeMillis())
        
        // Then: Should create correct ClipboardContent with fallback
        assertEquals(ClipboardContent.ContentType.UNKNOWN, content.type)
        assertEquals(fallbackText, String(content.data))
        assertEquals("text/plain", content.mimeType)
        assertEquals("accessibility_service", content.source)
        assertEquals(fallbackText.length.toLong(), content.size)
        assertTrue(content.metadata.containsKey("fallback"))
        assertEquals("true", content.metadata["fallback"])
    }
    
    @Test
    fun `handleClipboardChange processes clipboard content and calls callback`() = runTest {
        // Given: Service is created and monitoring
        service.onCreate()
        service.startClipboardMonitoring()
        
        val testText = "Test clipboard content"
        val clipData = mockk<ClipData>()
        val clipItem = mockk<ClipData.Item>()
        
        every { mockClipboardManager.primaryClip } returns clipData
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0) } returns clipItem
        every { clipItem.text } returns testText
        every { clipItem.htmlText } returns null
        every { clipItem.uri } returns null
        
        var capturedContent: ClipboardContent? = null
        var capturedTimestamp: Long? = null
        
        clipboardChangedCallback = { content, timestamp ->
            capturedContent = content
            capturedTimestamp = timestamp
        }
        
        // When: Handling clipboard change
        service.invokePrivate("handleClipboardChange")
        
        // Give some time for coroutine to complete
        kotlinx.coroutines.delay(100)
        
        // Then: Should call callback with correct content
        assertNotNull(capturedContent)
        assertNotNull(capturedTimestamp)
        assertEquals(ClipboardContent.ContentType.TEXT, capturedContent?.type)
        assertEquals(testText, String(capturedContent?.data ?: byteArrayOf()))
    }
    
    @Test
    fun `handleClipboardChange ignores empty clipboard`() = runTest {
        // Given: Service is created and monitoring
        service.onCreate()
        service.startClipboardMonitoring()
        
        every { mockClipboardManager.primaryClip } returns null
        
        var callbackCalled = false
        clipboardChangedCallback = { _, _ ->
            callbackCalled = true
        }
        
        // When: Handling clipboard change with empty clipboard
        service.invokePrivate("handleClipboardChange")
        
        // Give some time for coroutine to complete
        kotlinx.coroutines.delay(100)
        
        // Then: Should not call callback
        assertFalse(callbackCalled)
    }
    
    @Test
    fun `handleClipboardChange calls error callback on exception`() = runTest {
        // Given: Service is created and monitoring
        service.onCreate()
        service.startClipboardMonitoring()
        
        val testException = RuntimeException("Test exception")
        every { mockClipboardManager.primaryClip } throws testException
        
        var capturedError: Throwable? = null
        errorCallback = { throwable ->
            capturedError = throwable
        }
        
        // When: Handling clipboard change that throws exception
        service.invokePrivate("handleClipboardChange")
        
        // Give some time for coroutine to complete
        kotlinx.coroutines.delay(100)
        
        // Then: Should call error callback
        assertEquals(testException, capturedError)
    }
    
    // Helper extension function to invoke private methods for testing
    private inline fun <reified T> Any.invokePrivate(methodName: String, vararg args: Any?): T {
        val method = this::class.java.getDeclaredMethod(
            methodName,
            *args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(this, *args) as T
    }
    
    private fun Any.invokePrivate(methodName: String, vararg args: Any?) {
        val method = this::class.java.getDeclaredMethod(
            methodName,
            *args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        )
        method.isAccessible = true
        method.invoke(this, *args)
    }
}