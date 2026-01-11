package com.siw.clipboardsync.utils

import com.siw.clipboardsync.R

/**
 * MIME类型映射工具类
 * 提供文件扩展名到MIME类型的映射，以及MIME类型到图标的映射
 */
object MimeTypeMapping {
    
    private val extensionToMime = mapOf(
        // 文本文件
        "txt" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "csv" to "text/csv",
        "xml" to "text/xml",
        "md" to "text/markdown",
        "json" to "application/json",
        "js" to "application/javascript",
        
        // 图片文件
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        "heic" to "image/heic",
        "heif" to "image/heif",
        
        // 文档文件
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        
        // 压缩文件
        "zip" to "application/zip",
        "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        
        // 音频文件
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
        "wma" to "audio/x-ms-wma",
        
        // 视频文件
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "webm" to "video/webm",
        "m4v" to "video/x-m4v",
        "3gp" to "video/3gpp",
        
        // 可执行文件
        "apk" to "application/vnd.android.package-archive",
        "exe" to "application/x-msdownload",
        "dmg" to "application/x-apple-diskimage",
        
        // 其他
        "bin" to "application/octet-stream",
        "dat" to "application/octet-stream"
    )
    
    /**
     * 根据文件扩展名获取MIME类型
     * @param extension 文件扩展名（不含点号）
     * @return MIME类型字符串，未知类型返回 "application/octet-stream"
     */
    fun getMimeType(extension: String): String {
        return extensionToMime[extension.lowercase()] ?: "application/octet-stream"
    }
    
    /**
     * 根据文件名获取MIME类型
     * @param fileName 文件名
     * @return MIME类型字符串
     */
    fun getMimeTypeFromFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotEmpty()) {
            getMimeType(extension)
        } else {
            "application/octet-stream"
        }
    }
    
    /**
     * 根据MIME类型获取对应的图标资源ID
     * @param mimeType MIME类型字符串
     * @return 图标资源ID
     */
    fun getIconForMimeType(mimeType: String): Int {
        return when {
            mimeType.startsWith("image/") -> R.drawable.ic_file_image
            mimeType.startsWith("video/") -> R.drawable.ic_file_video
            mimeType.startsWith("audio/") -> R.drawable.ic_file_audio
            mimeType.startsWith("text/") -> R.drawable.ic_file_text
            mimeType.contains("pdf") -> R.drawable.ic_file_pdf
            mimeType.contains("word") || mimeType.contains("document") -> R.drawable.ic_file_word
            mimeType.contains("excel") || mimeType.contains("spreadsheet") -> R.drawable.ic_file_excel
            mimeType.contains("powerpoint") || mimeType.contains("presentation") -> R.drawable.ic_file_ppt
            mimeType.contains("zip") || mimeType.contains("rar") || 
                mimeType.contains("7z") || mimeType.contains("tar") || 
                mimeType.contains("gzip") || mimeType.contains("bzip") -> R.drawable.ic_file_archive
            mimeType.contains("android") || mimeType.contains("apk") -> R.drawable.ic_file_apk
            else -> R.drawable.ic_file_generic
        }
    }
    
    /**
     * 检查MIME类型是否为图片类型
     */
    fun isImageType(mimeType: String): Boolean = mimeType.startsWith("image/")
    
    /**
     * 检查MIME类型是否为视频类型
     */
    fun isVideoType(mimeType: String): Boolean = mimeType.startsWith("video/")
    
    /**
     * 检查MIME类型是否为音频类型
     */
    fun isAudioType(mimeType: String): Boolean = mimeType.startsWith("audio/")
    
    /**
     * 检查MIME类型是否为文本类型
     */
    fun isTextType(mimeType: String): Boolean {
        return mimeType.startsWith("text/") || 
               mimeType == "application/json" || 
               mimeType == "application/javascript" ||
               mimeType == "application/xml"
    }
    
    /**
     * 检查MIME类型是否为文档类型
     */
    fun isDocumentType(mimeType: String): Boolean {
        return mimeType.contains("pdf") ||
               mimeType.contains("word") ||
               mimeType.contains("document") ||
               mimeType.contains("excel") ||
               mimeType.contains("spreadsheet") ||
               mimeType.contains("powerpoint") ||
               mimeType.contains("presentation") ||
               mimeType.contains("opendocument")
    }
    
    /**
     * 获取MIME类型的友好显示名称
     */
    fun getDisplayName(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "图片"
            mimeType.startsWith("video/") -> "视频"
            mimeType.startsWith("audio/") -> "音频"
            mimeType.startsWith("text/") -> "文本"
            mimeType.contains("pdf") -> "PDF文档"
            mimeType.contains("word") || mimeType.contains("document") -> "Word文档"
            mimeType.contains("excel") || mimeType.contains("spreadsheet") -> "Excel表格"
            mimeType.contains("powerpoint") || mimeType.contains("presentation") -> "PPT演示"
            mimeType.contains("zip") || mimeType.contains("rar") || 
                mimeType.contains("7z") || mimeType.contains("tar") -> "压缩文件"
            mimeType.contains("android") || mimeType.contains("apk") -> "安装包"
            else -> "文件"
        }
    }
}
