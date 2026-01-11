package com.siw.clipboardsync.monitor.processor

import android.content.Context
import com.siw.clipboardsync.monitor.model.ClipboardContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.nio.charset.StandardCharsets

class FileProcessorTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    private lateinit var fileProcessor: FileProcessor
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        fileProcessor = FileProcessor(mockContext)
    }
    
    @Test
    fun `test canProcess returns true for file content`() {
        val fileContent = createFileContent("file:///path/to/file.txt")
        assertTrue(fileProcessor.canProcess(fileContent))
    }
    
    @Test
    fun `test canProcess returns true for URI content`() {
        val uriContent = createUriContent("https://example.com/file.pdf")
        assertTrue(fileProcessor.canProcess(uriContent))
    }
    
    @Test
    fun `test canProcess returns false for text content`() {
        val textContent = createTextContent("Hello, World!")
        assertFalse(fileProcessor.canProcess(textContent))
    }
    
    @Test
    fun `test canProcess returns true for unknown content with file URI`() {
        val unknownContent = createUnknownContent("file:///path/to/document.pdf")
        assertTrue(fileProcessor.canProcess(unknownContent))
    }
    
    @Test
    fun `test validate returns Valid for single file URI`() {
        val content = createFileContent("file:///path/to/file.txt")
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `test validate returns Valid for HTTP URL`() {
        val content = createUriContent("https://example.com/file.pdf")
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `test validate returns SizeExceeded for large content`() {
        val largeData = ByteArray(150 * 1024 * 1024) // 150MB
        val content = createFileContent("file:///path/to/file.txt").copy(
            data = largeData,
            size = largeData.size.toLong()
        )
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.SizeExceeded)
    }
    
    @Test
    fun `test validate returns SizeExceeded for too many files`() {
        val manyFiles = (1..60).map { "file:///path/to/file$it.txt" }.joinToString("\n")
        val content = createFileContent(manyFiles)
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.SizeExceeded)
    }
    
    @Test
    fun `test validate returns Invalid for unsupported URI scheme`() {
        val content = createFileContent("unsupported://path/to/file.txt")
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `test validate returns Invalid for malformed URI`() {
        val content = createFileContent("not a valid uri")
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `test validate returns Invalid for path too long`() {
        val longPath = "file:///" + "a".repeat(5000) + "/file.txt"
        val content = createFileContent(longPath)
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `test detectContentType returns URI for uri-list mime type`() {
        val data = "https://example.com/file.pdf".toByteArray()
        val result = fileProcessor.detectContentType(data, "text/uri-list")
        assertEquals(ClipboardContent.ContentType.URI, result)
    }
    
    @Test
    fun `test detectContentType returns FILE for application mime type`() {
        val data = "file:///path/to/file.pdf".toByteArray()
        val result = fileProcessor.detectContentType(data, "application/pdf")
        assertEquals(ClipboardContent.ContentType.FILE, result)
    }
    
    @Test
    fun `test detectContentType returns URI for URI list data`() {
        val uriListData = "https://example.com/file1.pdf\nhttps://example.com/file2.txt"
        val data = uriListData.toByteArray()
        val result = fileProcessor.detectContentType(data, null)
        assertEquals(ClipboardContent.ContentType.URI, result)
    }
    
    @Test
    fun `test detectContentType returns FILE for file path data`() {
        val data = "file:///path/to/document.pdf".toByteArray()
        val result = fileProcessor.detectContentType(data, null)
        assertEquals(ClipboardContent.ContentType.FILE, result)
    }
    
    @Test
    fun `test detectContentType returns null for text data`() {
        val data = "This is just plain text".toByteArray()
        val result = fileProcessor.detectContentType(data, null)
        assertNull(result)
    }
    
    @Test
    fun `test process returns Success for valid file URI`() = runTest {
        val content = createFileContent("file:///path/to/document.pdf")
        
        val result = fileProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        assertEquals(ClipboardContent.ContentType.FILE, successResult.processedContent.type)
        assertTrue(successResult.metadata.containsKey("uri_count"))
    }
    
    @Test
    fun `test process returns Success for HTTP URL`() = runTest {
        val content = createUriContent("https://example.com/file.pdf")
        
        val result = fileProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        assertEquals(ClipboardContent.ContentType.URI, successResult.processedContent.type)
        assertTrue(successResult.metadata.containsKey("web_url_count"))
    }
    
    @Test
    fun `test process returns Success for multiple URIs`() = runTest {
        val multipleUris = "https://example.com/file1.pdf\nfile:///path/to/file2.txt\nhttps://example.com/file3.doc"
        val content = createUriContent(multipleUris)
        
        val result = fileProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        val metadata = successResult.metadata
        assertEquals(3, (metadata["uri_count"] as String).toInt())
        assertTrue((metadata["web_url_count"] as String).toInt() > 0)
        assertTrue((metadata["local_file_count"] as String).toInt() > 0)
    }
    
    @Test
    fun `test process returns SizeExceeded for oversized content`() = runTest {
        val largeData = ByteArray(150 * 1024 * 1024) // 150MB
        val content = createFileContent("file:///path/to/file.txt").copy(
            data = largeData,
            size = largeData.size.toLong()
        )
        
        val result = fileProcessor.process(content)
        
        assertTrue(result is ProcessingResult.SizeExceeded)
    }
    
    @Test
    fun `test process handles invalid URIs gracefully`() = runTest {
        val mixedContent = "https://valid.com/file.pdf\ninvalid-uri\nfile:///valid/path.txt"
        val content = createUriContent(mixedContent)
        
        val result = fileProcessor.process(content)
        
        assertTrue(result is ProcessingResult.Success)
        val successResult = result as ProcessingResult.Success
        // Should process valid URIs and skip invalid ones
        assertTrue(successResult.metadata.containsKey("uri_count"))
    }
    
    @Test
    fun `test maxSizeLimit is set correctly`() {
        assertEquals(100 * 1024 * 1024L, fileProcessor.maxSizeLimit) // 100MB
    }
    
    @Test
    fun `test supportedType is FILE`() {
        assertEquals(ClipboardContent.ContentType.FILE, fileProcessor.supportedType)
    }
    
    @Test
    fun `test priority is set correctly`() {
        val expectedPriority = ContentTypeDetector.getPriority(ClipboardContent.ContentType.FILE)
        assertEquals(expectedPriority, fileProcessor.priority)
    }
    
    @Test
    fun `test URI scheme validation`() {
        // Valid schemes
        assertTrue(isValidUriScheme("file:///path/to/file.txt"))
        assertTrue(isValidUriScheme("https://example.com/file.pdf"))
        assertTrue(isValidUriScheme("http://example.com/file.pdf"))
        assertTrue(isValidUriScheme("ftp://example.com/file.pdf"))
        assertTrue(isValidUriScheme("content://provider/path"))
        
        // Invalid schemes
        assertFalse(isValidUriScheme("invalid://path"))
        assertFalse(isValidUriScheme("no-scheme-here"))
        assertFalse(isValidUriScheme(""))
    }
    
    @Test
    fun `test URI list parsing with comments`() {
        val uriListWithComments = """
            # This is a comment
            https://example.com/file1.pdf
            # Another comment
            file:///path/to/file2.txt
            
            https://example.com/file3.doc
        """.trimIndent()
        
        val content = createUriContent(uriListWithComments)
        val result = fileProcessor.validate(content)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `test empty URI list handling`() {
        val emptyContent = createUriContent("")
        val result = fileProcessor.validate(emptyContent)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `test whitespace-only URI list handling`() {
        val whitespaceContent = createUriContent("   \n\t  \n  ")
        val result = fileProcessor.validate(whitespaceContent)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `test file extension detection`() {
        assertTrue(hasFileExtension("document.pdf"))
        assertTrue(hasFileExtension("image.jpg"))
        assertTrue(hasFileExtension("archive.zip"))
        assertTrue(hasFileExtension("music.mp3"))
        assertFalse(hasFileExtension("no-extension"))
        assertFalse(hasFileExtension(""))
    }
    
    // Helper methods
    private fun createFileContent(uri: String): ClipboardContent {
        val data = uri.toByteArray(StandardCharsets.UTF_8)
        return ClipboardContent(
            type = ClipboardContent.ContentType.FILE,
            data = data,
            mimeType = "application/octet-stream",
            source = "test",
            size = data.size.toLong()
        )
    }
    
    private fun createUriContent(uri: String): ClipboardContent {
        val data = uri.toByteArray(StandardCharsets.UTF_8)
        return ClipboardContent(
            type = ClipboardContent.ContentType.URI,
            data = data,
            mimeType = "text/uri-list",
            source = "test",
            size = data.size.toLong()
        )
    }
    
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
    
    private fun createUnknownContent(content: String): ClipboardContent {
        val data = content.toByteArray(StandardCharsets.UTF_8)
        return ClipboardContent(
            type = ClipboardContent.ContentType.UNKNOWN,
            data = data,
            mimeType = "application/octet-stream",
            source = "test",
            size = data.size.toLong()
        )
    }
    
    private fun isValidUriScheme(uri: String): Boolean {
        val supportedSchemes = setOf("file", "content", "android.resource", "http", "https", "ftp")
        return try {
            val scheme = uri.substringBefore("://").lowercase()
            scheme in supportedSchemes
        } catch (e: Exception) {
            false
        }
    }
    
    private fun hasFileExtension(filename: String): Boolean {
        val fileExtensions = setOf(
            "txt", "doc", "docx", "pdf", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z", "tar", "gz", "apk", "exe", "dmg",
            "mp3", "mp4", "avi", "mov", "wav", "flac", "mkv", "jpg", "jpeg", "png"
        )
        val extension = filename.substringAfterLast(".", "").lowercase()
        return extension in fileExtensions
    }
}