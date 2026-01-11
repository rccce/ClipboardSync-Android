package com.siw.clipboardsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.siw.clipboardsync.manager.FileSyncManager
import com.siw.clipboardsync.ui.theme.ClipboardSyncTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity to receive shared files from other apps.
 * Users can share files to ClipboardSync to sync them across devices.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "ShareReceiverActivity"
    }
    
    @Inject
    lateinit var fileSyncManager: FileSyncManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "ShareReceiverActivity created")
        
        setContent {
            ClipboardSyncTheme {
                ShareReceiverScreen(
                    onDismiss = { finish() }
                )
            }
        }
        
        // Handle the incoming share intent
        handleShareIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }
    
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) {
            Log.w(TAG, "Received null intent")
            finish()
            return
        }
        
        Log.d(TAG, "Handling share intent: action=${intent.action}, type=${intent.type}")
        
        when (intent.action) {
            Intent.ACTION_SEND -> handleSingleShare(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleShare(intent)
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
                Toast.makeText(this, "不支持的分享类型", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun handleSingleShare(intent: Intent) {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        
        if (uri != null) {
            Log.d(TAG, "Received single file: $uri")
            uploadFile(uri)
        } else {
            // Maybe it's text content
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                Log.d(TAG, "Received text content: ${text.take(50)}...")
                // For text, we could sync it directly, but for now just show a message
                Toast.makeText(this, "文本内容请使用复制功能同步", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Log.w(TAG, "No content in share intent")
                Toast.makeText(this, "未找到可分享的内容", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun handleMultipleShare(intent: Intent) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        
        if (uris.isNullOrEmpty()) {
            Log.w(TAG, "No URIs in multiple share intent")
            Toast.makeText(this, "未找到可分享的文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        Log.d(TAG, "Received ${uris.size} files")
        
        // For now, only upload the first file
        // TODO: Support multiple file upload
        if (uris.size > 1) {
            Toast.makeText(this, "暂时只支持单文件同步，将同步第一个文件", Toast.LENGTH_SHORT).show()
        }
        
        uploadFile(uris.first())
    }
    
    private fun uploadFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting file upload: $uri")
                
                val result = fileSyncManager.uploadAndSyncFile(uri)
                
                if (result.isSuccess) {
                    val item = result.getOrNull()
                    Log.d(TAG, "File uploaded successfully: ${item?.fileName}")
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "文件已同步: ${item?.fileName ?: "unknown"}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "File upload failed: $error")
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "同步失败: $error",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading file", e)
                Toast.makeText(
                    this@ShareReceiverActivity,
                    "同步失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                finish()
            }
        }
    }
}

@Composable
fun ShareReceiverScreen(
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "正在同步文件...",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "文件将同步到所有已连接的设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
