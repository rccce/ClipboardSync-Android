package com.siw.clipboardsync.presentation.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.presentation.monitoring.MonitoringStatusIndicator
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToMonitoringSettings: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Permission launcher for Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.checkPermissions()
    }
    
    LaunchedEffect(Unit) {
        // Request permissions if needed
        if (!uiState.hasRequiredPermissions && uiState.missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(uiState.missingPermissions.toTypedArray())
        }
    }
    
    // Refresh service status when returning to the app
    LaunchedEffect(uiState.syncStatus) {
        // When sync status changes, refresh the service status to keep UI in sync
        viewModel.refreshServiceStatus()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "ClipboardSync",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Advanced Monitoring Status
        MonitoringStatusIndicator(
            isMonitoring = uiState.isAdvancedMonitoring,
            currentMethod = uiState.currentMonitoringMethod,
            hasPermissions = uiState.hasRequiredPermissions,
            onSettingsClick = onNavigateToMonitoringSettings
        )
        
        // Debug: Monitoring Diagnostics Button
        Button(
            onClick = { viewModel.showMonitoringDiagnostics() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show Monitoring Diagnostics")
        }
        
        // Request Root Access Button
        Button(
            onClick = { viewModel.requestRootAccess() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("Request Root Access")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Unified Clipboard Sync Card
        UnifiedClipboardSyncCard(
            isServiceRunning = uiState.isServiceRunning,
            hasPermissions = uiState.hasRequiredPermissions,
            missingPermissions = uiState.missingPermissions,
            syncStatus = uiState.syncStatus,
            autoUpdateEnabled = uiState.autoUpdateClipboard,
            onToggleWebSocket = viewModel::toggleWebSocketConnection,
            onToggleAutoUpdate = viewModel::toggleAutoUpdateClipboard,
            onRequestPermissions = { 
                permissionLauncher.launch(uiState.missingPermissions.toTypedArray())
            }
        )
        
        // Incoming Update Notification
        uiState.lastIncomingUpdate?.let { incomingItem ->
            Spacer(modifier = Modifier.height(16.dp))
            IncomingUpdateCard(
                clipboardItem = incomingItem,
                onApply = viewModel::applyIncomingUpdate,
                onDismiss = viewModel::dismissIncomingUpdate
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Current Clipboard
        CurrentClipboardCard(
            currentClipboard = uiState.currentClipboard,
            onRefresh = { viewModel.refreshClipboardHistory() }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Clipboard History
        Text(
            text = "Recent Clipboard History",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            ClipboardHistoryList(
                history = uiState.clipboardHistory,
                onCopyToClipboard = { content -> viewModel.copyToClipboard(content) },
                onDeleteItem = { itemId -> viewModel.deleteClipboardItem(itemId) }
            )
        }
        
        // Error message
        uiState.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedClipboardSyncCard(
    isServiceRunning: Boolean,
    hasPermissions: Boolean,
    missingPermissions: List<String>,
    syncStatus: ClipboardSyncManager.SyncStatus,
    autoUpdateEnabled: Boolean,
    onToggleWebSocket: () -> Unit,
    onToggleAutoUpdate: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !hasPermissions -> MaterialTheme.colorScheme.errorContainer
                isServiceRunning && syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                isServiceRunning -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with status and main toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                !hasPermissions -> Icons.Default.Warning
                                isServiceRunning && syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> Icons.Default.CheckCircle
                                isServiceRunning -> Icons.Default.Refresh
                                else -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when {
                                !hasPermissions -> MaterialTheme.colorScheme.onErrorContainer
                                isServiceRunning && syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                                isServiceRunning -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                !hasPermissions -> "Clipboard Sync - Permissions Required"
                                syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> "Clipboard Sync - Connected"
                                syncStatus == ClipboardSyncManager.SyncStatus.CONNECTING -> "Clipboard Sync - Connecting..."
                                else -> "Clipboard Sync - Disconnected"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                !hasPermissions -> MaterialTheme.colorScheme.onErrorContainer
                                syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                                syncStatus == ClipboardSyncManager.SyncStatus.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = when {
                            !hasPermissions -> "Grant permissions to enable clipboard sync"
                            syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> "Your clipboard is synced across devices"
                            syncStatus == ClipboardSyncManager.SyncStatus.CONNECTING -> "Connecting to sync server..."
                            else -> "Connect to sync clipboard across devices"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            !hasPermissions -> MaterialTheme.colorScheme.onErrorContainer
                            syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                            syncStatus == ClipboardSyncManager.SyncStatus.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                
                // Main control
                when {
                    !hasPermissions -> {
                        Button(
                            onClick = onRequestPermissions,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Grant Permissions")
                        }
                    }
                    else -> {
                        Switch(
                            checked = syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED || 
                                     syncStatus == ClipboardSyncManager.SyncStatus.CONNECTING,
                            onCheckedChange = { onToggleWebSocket() }
                        )
                    }
                }
            }
            
            // Auto-update toggle (only show when WebSocket is connected)
            if (syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED && hasPermissions) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(
                    color = when {
                        syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-apply incoming updates",
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    Switch(
                        checked = autoUpdateEnabled,
                        onCheckedChange = { onToggleAutoUpdate() }
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentClipboardCard(
    currentClipboard: String?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Clipboard",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = currentClipboard?.take(100) ?: "No content",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ClipboardHistoryList(
    history: List<ClipboardItem>,
    onCopyToClipboard: (String) -> Unit,
    onDeleteItem: (String) -> Unit
) {
    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No clipboard history yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history) { item ->
                ClipboardHistoryItem(
                    item = item,
                    onCopy = { onCopyToClipboard(item.content) },
                    onDelete = { onDeleteItem(item.id) }
                )
            }
        }
    }
}

@Composable
private fun ClipboardHistoryItem(
    item: ClipboardItem,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.content.take(100),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = formatDate(item.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Copy",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun formatDate(dateString: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        val outputFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val date = inputFormat.parse(dateString)
        outputFormat.format(date ?: Date())
    } catch (e: Exception) {
        dateString.take(16) // Fallback to first 16 characters
    }
}


@Composable
private fun IncomingUpdateCard(
    clipboardItem: ClipboardItem,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "New clipboard update",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "From: ${clipboardItem.deviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = clipboardItem.content.take(100) + if (clipboardItem.content.length > 100) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApply) {
                    Text("Apply to Clipboard")
                }
            }
        }
    }
}