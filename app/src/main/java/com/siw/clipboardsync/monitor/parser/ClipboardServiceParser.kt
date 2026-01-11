package com.siw.clipboardsync.monitor.parser

import android.util.Log

/**
 * Parser for clipboard service output from various root-based commands.
 * 
 * Supports parsing output from:
 * - `service call clipboard 2` (hex-encoded format)
 * - `dumpsys clipboard` (text format)
 * - `content query --uri content://clipboard/text` (content provider format)
 * 
 * Requirements: 2.4
 */
object ClipboardServiceParser {
    
    private const val TAG = "ClipboardServiceParser"
    
    /**
     * Result of parsing clipboard service output
     */
    data class ParseResult(
        val success: Boolean,
        val content: String? = null,
        val mimeType: String? = null,
        val error: String? = null,
        val parseMethod: String? = null
    )
    
    /**
     * Parses output from `service call clipboard 2` command.
     * The output is typically in hex-encoded format.
     * 
     * Example output format:
     * Result: Parcel(
     *   0x00000000: 00000000 00000001 00000001 00000010 '................'
     *   0x00000010: 00740065 00780074 002f0070 006c0061 '.t.e.x.t./.p.l.a'
     *   ...
     * )
     * 
     * @param output The raw output from service call clipboard command
     * @return ParseResult containing the extracted text content or error
     */
    fun parseServiceCallOutput(output: String): ParseResult {
        if (output.isBlank()) {
            return ParseResult(success = false, error = "Empty output")
        }
        
        try {
            // Method 1: Try to parse hex-encoded content between quotes
            val hexPattern = Regex("'([0-9a-fA-F .]+)'")
            val hexMatches = hexPattern.findAll(output).toList()
            
            if (hexMatches.isNotEmpty()) {
                val hexContent = hexMatches.joinToString("") { match ->
                    match.groupValues[1].replace(" ", "").replace(".", "")
                }
                
                if (hexContent.isNotEmpty()) {
                    val decoded = decodeHexToString(hexContent)
                    if (decoded != null && decoded.isNotEmpty()) {
                        Log.d(TAG, "Parsed service call output via hex method: ${decoded.take(50)}")
                        return ParseResult(
                            success = true,
                            content = decoded,
                            parseMethod = "hex_decode"
                        )
                    }
                }
            }
            
            // Method 2: Try to extract UTF-16 encoded text
            val utf16Pattern = Regex("([0-9a-fA-F]{8}:.*)")
            val utf16Matches = utf16Pattern.findAll(output).toList()
            
            if (utf16Matches.isNotEmpty()) {
                val hexBytes = StringBuilder()
                for (match in utf16Matches) {
                    val line = match.value
                    // Extract hex values from the line (skip the address part)
                    val hexPart = line.substringAfter(":").substringBefore("'").trim()
                    val hexValues = hexPart.split(" ").filter { it.length == 8 }
                    for (hexValue in hexValues) {
                        hexBytes.append(hexValue)
                    }
                }
                
                if (hexBytes.isNotEmpty()) {
                    val decoded = decodeUtf16Hex(hexBytes.toString())
                    if (decoded != null && decoded.isNotEmpty()) {
                        Log.d(TAG, "Parsed service call output via UTF-16 method: ${decoded.take(50)}")
                        return ParseResult(
                            success = true,
                            content = decoded,
                            parseMethod = "utf16_decode"
                        )
                    }
                }
            }
            
            // Method 3: Try to find plain text content
            val plainTextPattern = Regex("text=\\{([^}]+)\\}")
            val plainTextMatch = plainTextPattern.find(output)
            if (plainTextMatch != null) {
                val content = plainTextMatch.groupValues[1]
                Log.d(TAG, "Parsed service call output via plain text method: ${content.take(50)}")
                return ParseResult(
                    success = true,
                    content = content,
                    parseMethod = "plain_text"
                )
            }
            
            return ParseResult(success = false, error = "Could not parse service call output")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing service call output", e)
            return ParseResult(success = false, error = e.message)
        }
    }
    
    /**
     * Parses output from `dumpsys clipboard` command.
     * 
     * Example output format:
     * Current clipboard owner: com.example.app
     * ClipData { text/plain {T:Hello World} }
     * 
     * @param output The raw output from dumpsys clipboard command
     * @return ParseResult containing the extracted text content or error
     */
    fun parseDumpsysOutput(output: String): ParseResult {
        if (output.isBlank()) {
            return ParseResult(success = false, error = "Empty output")
        }
        
        try {
            val lines = output.split("\n")
            
            // Method 1: Look for ClipData with text content
            for (line in lines) {
                // Pattern: ClipData { text/plain {T:content} }
                if (line.contains("ClipData") && line.contains("text/plain")) {
                    val textPattern = Regex("\\{T:([^}]+)\\}")
                    val textMatch = textPattern.find(line)
                    if (textMatch != null) {
                        val content = textMatch.groupValues[1]
                        Log.d(TAG, "Parsed dumpsys output via ClipData T: pattern: ${content.take(50)}")
                        return ParseResult(
                            success = true,
                            content = content,
                            mimeType = "text/plain",
                            parseMethod = "clipdata_t"
                        )
                    }
                }
                
                // Pattern: text/plain "content"
                if (line.contains("text/plain")) {
                    val quotedPattern = Regex("\"([^\"]+)\"")
                    val quotedMatch = quotedPattern.find(line)
                    if (quotedMatch != null) {
                        val content = quotedMatch.groupValues[1]
                        Log.d(TAG, "Parsed dumpsys output via quoted pattern: ${content.take(50)}")
                        return ParseResult(
                            success = true,
                            content = content,
                            mimeType = "text/plain",
                            parseMethod = "quoted_text"
                        )
                    }
                }
            }
            
            // Method 2: Look for primary clip content
            for (i in lines.indices) {
                val line = lines[i]
                if (line.contains("mPrimaryClip") || line.contains("Primary clip")) {
                    // Check next few lines for content
                    for (j in i until minOf(i + 5, lines.size)) {
                        val contentLine = lines[j]
                        val quotedPattern = Regex("\"([^\"]+)\"")
                        val quotedMatch = quotedPattern.find(contentLine)
                        if (quotedMatch != null) {
                            val content = quotedMatch.groupValues[1]
                            Log.d(TAG, "Parsed dumpsys output via mPrimaryClip: ${content.take(50)}")
                            return ParseResult(
                                success = true,
                                content = content,
                                parseMethod = "primary_clip"
                            )
                        }
                    }
                }
            }
            
            // Method 3: Look for any text content in ClipData
            for (line in lines) {
                if (line.contains("ClipData")) {
                    val anyTextPattern = Regex("\\{([^{}]+)\\}")
                    val matches = anyTextPattern.findAll(line).toList()
                    for (match in matches) {
                        val content = match.groupValues[1]
                        if (content.isNotBlank() && !content.startsWith("T:") && content.length > 2) {
                            Log.d(TAG, "Parsed dumpsys output via generic ClipData: ${content.take(50)}")
                            return ParseResult(
                                success = true,
                                content = content,
                                parseMethod = "generic_clipdata"
                            )
                        }
                    }
                }
            }
            
            return ParseResult(success = false, error = "Could not find clipboard content in dumpsys output")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing dumpsys output", e)
            return ParseResult(success = false, error = e.message)
        }
    }

    
    /**
     * Parses output from `content query --uri content://clipboard/text` command.
     * 
     * @param output The raw output from content query command
     * @return ParseResult containing the extracted text content or error
     */
    fun parseContentQueryOutput(output: String): ParseResult {
        if (output.isBlank()) {
            return ParseResult(success = false, error = "Empty output")
        }
        
        try {
            // Pattern: Row: 0 _data=content, _id=1
            val rowPattern = Regex("Row:\\s*\\d+\\s+(.+)")
            val rowMatch = rowPattern.find(output)
            
            if (rowMatch != null) {
                val rowContent = rowMatch.groupValues[1]
                
                // Extract _data field
                val dataPattern = Regex("_data=([^,]+)")
                val dataMatch = dataPattern.find(rowContent)
                if (dataMatch != null) {
                    val content = dataMatch.groupValues[1].trim()
                    Log.d(TAG, "Parsed content query output: ${content.take(50)}")
                    return ParseResult(
                        success = true,
                        content = content,
                        parseMethod = "content_query"
                    )
                }
            }
            
            // Fallback: try to extract any meaningful content
            val lines = output.split("\n").filter { it.isNotBlank() }
            for (line in lines) {
                if (!line.startsWith("Row:") && line.length > 2) {
                    Log.d(TAG, "Parsed content query output (fallback): ${line.take(50)}")
                    return ParseResult(
                        success = true,
                        content = line.trim(),
                        parseMethod = "content_query_fallback"
                    )
                }
            }
            
            return ParseResult(success = false, error = "Could not parse content query output")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing content query output", e)
            return ParseResult(success = false, error = e.message)
        }
    }
    
    /**
     * Attempts to parse clipboard output using all available methods.
     * Tries each parser in order and returns the first successful result.
     * 
     * @param output The raw output from any clipboard command
     * @return ParseResult containing the extracted text content or error
     */
    fun parseAny(output: String): ParseResult {
        if (output.isBlank()) {
            return ParseResult(success = false, error = "Empty output")
        }
        
        // Try service call parser first (most common for root access)
        val serviceCallResult = parseServiceCallOutput(output)
        if (serviceCallResult.success) {
            return serviceCallResult
        }
        
        // Try dumpsys parser
        val dumpsysResult = parseDumpsysOutput(output)
        if (dumpsysResult.success) {
            return dumpsysResult
        }
        
        // Try content query parser
        val contentQueryResult = parseContentQueryOutput(output)
        if (contentQueryResult.success) {
            return contentQueryResult
        }
        
        return ParseResult(
            success = false, 
            error = "All parsing methods failed"
        )
    }
    
    /**
     * Decodes a hex string to a regular string.
     * Handles both ASCII and UTF-16 encoded content.
     */
    private fun decodeHexToString(hexString: String): String? {
        return try {
            if (hexString.length < 2) return null
            
            val cleanHex = hexString.replace(" ", "").replace(".", "")
            
            // Try UTF-16 LE decoding first (common for Android clipboard)
            if (cleanHex.length >= 4) {
                val utf16Result = decodeUtf16Hex(cleanHex)
                if (utf16Result != null && utf16Result.isNotEmpty() && isPrintable(utf16Result)) {
                    return utf16Result
                }
            }
            
            // Fallback to ASCII decoding
            val bytes = cleanHex.chunked(2).mapNotNull { 
                try { it.toInt(16).toByte() } catch (e: Exception) { null }
            }.toByteArray()
            
            val result = String(bytes, Charsets.UTF_8).trim('\u0000')
            if (isPrintable(result)) result else null
            
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding hex string", e)
            null
        }
    }
    
    /**
     * Decodes UTF-16 LE encoded hex string.
     */
    private fun decodeUtf16Hex(hexString: String): String? {
        return try {
            if (hexString.length < 4) return null
            
            val cleanHex = hexString.replace(" ", "")
            
            // Parse as UTF-16 LE (Little Endian)
            val chars = StringBuilder()
            var i = 0
            while (i + 4 <= cleanHex.length) {
                // Read 4 hex chars (2 bytes) as UTF-16 LE
                val lowByte = cleanHex.substring(i, i + 2).toInt(16)
                val highByte = cleanHex.substring(i + 2, i + 4).toInt(16)
                val charCode = (highByte shl 8) or lowByte
                
                if (charCode != 0) {
                    chars.append(charCode.toChar())
                }
                i += 4
            }
            
            val result = chars.toString().trim()
            if (result.isNotEmpty() && isPrintable(result)) result else null
            
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding UTF-16 hex", e)
            null
        }
    }
    
    /**
     * Checks if a string contains mostly printable characters.
     */
    private fun isPrintable(str: String): Boolean {
        if (str.isEmpty()) return false
        
        var printableCount = 0
        for (char in str) {
            if (char.isLetterOrDigit() || char.isWhitespace() || char in "!@#$%^&*()_+-=[]{}|;':\",./<>?`~") {
                printableCount++
            }
        }
        
        return printableCount.toFloat() / str.length > 0.7f
    }
    
    /**
     * Extracts MIME type from clipboard output if available.
     */
    fun extractMimeType(output: String): String? {
        return try {
            // Look for common MIME type patterns
            val mimePatterns = listOf(
                Regex("text/plain"),
                Regex("text/html"),
                Regex("image/\\w+"),
                Regex("application/\\w+")
            )
            
            for (pattern in mimePatterns) {
                val match = pattern.find(output)
                if (match != null) {
                    return match.value
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
}
