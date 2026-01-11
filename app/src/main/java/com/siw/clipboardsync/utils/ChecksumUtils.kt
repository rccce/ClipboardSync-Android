package com.siw.clipboardsync.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.security.MessageDigest

/**
 * Checksum计算工具类
 * 提供SHA-256校验和计算功能
 */
object ChecksumUtils {
    
    private const val TAG = "ChecksumUtils"
    private const val BUFFER_SIZE = 8192
    
    /**
     * 计算字节数组的SHA-256校验和
     * @param data 字节数组
     * @return 十六进制格式的校验和字符串
     */
    fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.toHexString()
    }
    
    /**
     * 计算输入流的SHA-256校验和（流式计算，适合大文件）
     * @param inputStream 输入流
     * @return 十六进制格式的校验和字符串
     */
    fun calculateChecksum(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        
        return digest.digest().toHexString()
    }
    
    /**
     * 计算文件URI的SHA-256校验和
     * @param context Android上下文
     * @param uri 文件URI
     * @return 十六进制格式的校验和字符串，失败返回null
     */
    fun calculateChecksum(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                calculateChecksum(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating checksum for URI: $uri", e)
            null
        }
    }
    
    /**
     * 计算字符串的SHA-256校验和
     * @param text 字符串
     * @return 十六进制格式的校验和字符串
     */
    fun calculateChecksumFromString(text: String): String {
        return calculateChecksum(text.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * 验证数据的校验和是否匹配
     * @param data 字节数组
     * @param expectedChecksum 期望的校验和
     * @return 是否匹配
     */
    fun verifyChecksum(data: ByteArray, expectedChecksum: String): Boolean {
        val actualChecksum = calculateChecksum(data)
        val matches = actualChecksum.equals(expectedChecksum, ignoreCase = true)
        if (!matches) {
            Log.w(TAG, "Checksum mismatch: expected=$expectedChecksum, actual=$actualChecksum")
        }
        return matches
    }
    
    /**
     * 验证文件的校验和是否匹配
     * @param context Android上下文
     * @param uri 文件URI
     * @param expectedChecksum 期望的校验和
     * @return 是否匹配
     */
    fun verifyChecksum(context: Context, uri: Uri, expectedChecksum: String): Boolean {
        val actualChecksum = calculateChecksum(context, uri) ?: return false
        val matches = actualChecksum.equals(expectedChecksum, ignoreCase = true)
        if (!matches) {
            Log.w(TAG, "File checksum mismatch: expected=$expectedChecksum, actual=$actualChecksum")
        }
        return matches
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
