package com.siw.clipboardsync.monitor.processor

import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

class TextProcessorTest {
    
    private lateinit var textProcessor: TextProcessor
    
    @Before
    fun setUp() {
        textProcessor = TextProcessor()
    }
    
    @Test
    fun `test canProcess returns true for text content`() {
        val textContent = createTextContent("Hello, World!")
        assertTrue(textProcessor.canProcess(textContent))
    }
    
    @Test
    fun `test canProcess returns true for HTML content`() {
        val htmlContent = createHtmlContent("<p>Hello, World!</p>")
        assertTrue(textProcessor.canProcess(htmlContent))
    }
    
    @Test
    fun `test canProcess returns false for image content`() {
        val imageContent = createImageContent(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        assertFalse(textProcessor.canProcess(imageContent))
    }
    
    @Test
    fun `test validate returns Valid for normal text`() {
        val content = createTextContent("This is a normal text content.")
        val result = textProcessor.validate(content)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `test validate returns SizeExceeded for large content`() {
        val largeText = "a".repeat(600_000) // Exceeds MAX_TEXT_LENGTH
        val content = createTextContent(largeText)
        val result = textProcessor.validate(content)
        assertTrue(result is ValidationResult.SizeExceeded)
    }
    
    @Test
    fun `test validate returns SizeExceeded for content exceeding byte limit`() {
        val content = createTextContent("test").copy(size = 2 * 1024 * 1024) // 2MB
        val result = textProcessor.validate(content)
        assertTrue(result is ValidationResult.SizeExceeded)
    }
    
    @Test
    fun `test process returns Success for valid text`() = runTest {
        val originalText = "  Hello,\r\nWorld!  \r"
        val content = createTextContent(originalText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        
        val processedText = String(successResult.processedContent.data, StandardCharsets.UTF_8)
        assertEquals("Hello,\nWorld!", processedText)
        
        // Check metadata
        assertTrue(successResult.metadata.containsKey("text_length"))
        assertTrue(successResult.metadata.containsKey("word_count"))
        assertTrue(successResult.metadata.containsKey("line_count"))
    }
    
    @Test
    fun `test process returns Success for HTML content`() = runTest {
        val htmlText = "<html><body><p>Hello, World!</p></body></html>"
        val content = createTextContent(htmlText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        
        assertEquals(ClipboardContent.ContentType.HTML, successResult.processedContent.type)
        assertTrue(successResult.metadata["is_html"] as Boolean)
    }
    
    @Test
    fun `test process returns SizeExceeded for large content`() = runTest {
        val largeText = "a".repeat(600_000)
        val content = createTextContent(largeText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.SizeExceeded)
    }
    
    @Test
    fun `test detectContentType returns TEXT for plain text mime type`() {
        val data = "Hello, World!".toByteArray(StandardCharsets.UTF_8)
        val result = textProcessor.detectContentType(data, "text/plain")
        assertEquals(ClipboardContent.ContentType.TEXT, result)
    }
    
    @Test
    fun `test detectContentType returns HTML for HTML mime type`() {
        val data = "<p>Hello</p>".toByteArray(StandardCharsets.UTF_8)
        val result = textProcessor.detectContentType(data, "text/html")
        assertEquals(ClipboardContent.ContentType.HTML, result)
    }
    
    @Test
    fun `test detectContentType returns TEXT for text-like data`() {
        val data = "This looks like text content".toByteArray(StandardCharsets.UTF_8)
        val result = textProcessor.detectContentType(data, null)
        assertEquals(ClipboardContent.ContentType.TEXT, result)
    }
    
    @Test
    fun `test detectContentType returns null for binary data`() {
        val binaryData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val result = textProcessor.detectContentType(binaryData, null)
        assertNull(result)
    }
    
    @Test
    fun `test text normalization removes excessive whitespace`() = runTest {
        val messyText = "  \r\n  Hello,\r\n\r\n  World!  \r\n  "
        val content = createTextContent(messyText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val processedText = String((result as ProcessingResult.Success).processedContent.data, StandardCharsets.UTF_8)
        assertEquals("Hello,\n\n  World!", processedText)
    }
    
    @Test
    fun `test metadata extraction for text content`() = runTest {
        val text = "Hello, World!\nThis is a test.\nWith multiple lines."
        val content = createTextContent(text)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val metadata = (result as ProcessingResult.Success).metadata
        
        assertEquals(text.length, metadata["text_length"])
        assertEquals(3, metadata["line_count"])
        assertEquals(8, metadata["word_count"]) // "Hello, World! This is a test. With multiple lines."
        assertEquals(false, metadata["is_html"])
        assertEquals(false, metadata["is_url"])
    }
    
    @Test
    fun `test URL detection in text`() = runTest {
        val urlText = "https://www.example.com"
        val content = createTextContent(urlText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val metadata = (result as ProcessingResult.Success).metadata
        assertEquals(true, metadata["is_url"])
    }
    
    @Test
    fun `test HTML detection in text`() = runTest {
        val htmlText = "<div>Hello <span>World</span></div>"
        val content = createTextContent(htmlText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        assertEquals(ClipboardContent.ContentType.HTML, successResult.processedContent.type)
        assertEquals(true, successResult.metadata["is_html"])
    }
    
    @Test
    fun `test special characters detection`() = runTest {
        val specialText = "Hello 世界! 🌍 Special chars: ñáéíóú"
        val content = createTextContent(specialText)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val metadata = (result as ProcessingResult.Success).metadata
        assertEquals(true, metadata["has_special_chars"])
    }
    
    @Test
    fun `test encoding detection for UTF-8`() = runTest {
        val utf8Text = "Hello, 世界!"
        val content = createTextContent(utf8Text)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val metadata = (result as ProcessingResult.Success).metadata
        assertEquals("UTF-8", metadata["encoding"])
    }
    
    @Test
    fun `test word counting accuracy`() = runTest {
        val text = "One two three four five"
        val content = createTextContent(text)
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val metadata = (result as ProcessingResult.Success).metadata
        assertEquals(5, metadata["word_count"])
    }
    
    @Test
    fun `test empty text handling`() = runTest {
        val content = createTextContent("")
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val processedText = String((result as ProcessingResult.Success).processedContent.data, StandardCharsets.UTF_8)
        assertEquals("", processedText)
    }
    
    @Test
    fun `test whitespace-only text handling`() = runTest {
        val content = createTextContent("   \n\r\n   \t  ")
        
        val result = textProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val processedText = String((result as ProcessingResult.Success).processedContent.data, StandardCharsets.UTF_8)
        assertEquals("", processedText)
    }
    
    // Helper methods
    private fun createTextContent(text: String): ClipboardContent {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        return ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = data,
            mimeType = "text/plain",
            source = "test",
            size = data.size.toLong()
        )
    }
    
    private fun createHtmlContent(html: String): ClipboardContent {
        val data = html.toByteArray(StandardCharsets.UTF_8)
        return ClipboardContent(
            type = ClipboardContent.ContentType.HTML,
            data = data,
            mimeType = "text/html",
            source = "test",
            size = data.size.toLong()
        )
    }
    
    private fun createImageContent(data: ByteArray): ClipboardContent {
        return ClipboardContent(
            type = ClipboardContent.ContentType.IMAGE,
            data = data,
            mimeType = "image/png",
            source = "test",
            size = data.size.toLong()
        )
    }
}