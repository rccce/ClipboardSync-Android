package com.siw.clipboardsync.presentation.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.siw.clipboardsync.data.model.*
import com.siw.clipboardsync.manager.ClipboardSyncManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemStatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: SystemStatusViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.refreshSystemStatus()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Status") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshSystemStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                uiState.systemStatus?.let { status ->
                    // Root Status Card
                    item {
                        SystemStatusCard(
                            title = "Root Access",
                            icon = Icons.Default.Lock,
                            status = status.rootStatus.statusText,
                            statusColor = status.rootStatus.statusColor,
                            details = buildList {
                                add("Root Method" to status.rootStatus.rootMethod.name)
                                add("Root Accessible" to if (status.rootStatus.isRootAccessible) "Yes" else "No")
                                add("System Hooks" to if (status.rootStatus.hasSystemHooks) "Available" else "Not Available")
                                add("Xposed Framework" to if (status.rootStatus.hasXposedFramework) "Available" else "Not Available")
                                add("Native Access" to if (status.rootStatus.hasNativeAccess) "Available" else "Not Available")
                                status.rootStatus.suBinaryPath?.let { path ->
                                    add("SU Binary Path" to path)
                                }
                            }
                        )
                    }
                    
                    // Monitoring Status Card
                    item {
                        SystemStatusCard(
                            title = "Clipboard Monitoring",
                            icon = Icons.Default.Settings,
                            status = status.monitoringStatus.statusText,
                            statusColor = status.monitoringStatus.statusColor,
                            details = buildList {
                                add("Currently Monitoring" to if (status.monitoringStatus.isMonitoring) "Yes" else "No")
                                status.monitoringStatus.currentMethod?.let { method ->
                                    add("Active Method" to method.name.replace("_", " "))
                                }
                                add("Available Methods" to "${status.monitoringStatus.availableMethods.size}")
                            },
                            actions = if (!status.monitoringStatus.isMonitoring) {
                                listOf("Start Monitoring" to { viewModel.startMonitoring() })
                            } else {
                                listOf("Stop Monitoring" to { viewModel.stopMonitoring() })
                            }
                        )
                    }
                    
                    // Connection Status Card
                    item {
                        SystemStatusCard(
                            title = "WebSocket Connection",
                            icon = Icons.Default.Info,
                            status = status.connectionStatus.statusText,
                            statusColor = status.connectionStatus.statusColor,
                            details = buildList {
                                add("Service Running" to if (status.connectionStatus.isServiceRunning) "Yes" else "No")
                                add("Sync Status" to status.connectionStatus.syncStatus.name)
                                status.connectionStatus.lastSyncTime?.let { time ->
                                    val formatter = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                                    add("Last Sync" to formatter.format(Date(time)))
                                }
                                status.connectionStatus.connectionLatency?.let { latency ->
                                    add("Latency" to "${latency}ms")
                                }
                            },
                            actions = when (status.connectionStatus.syncStatus) {
                                ClipboardSyncManager.SyncStatus.DISCONNECTED -> {
                                    listOf("Connect" to { viewModel.connectWebSocket() })
                                }
                                ClipboardSyncManager.SyncStatus.CONNECTED -> {
                                    listOf("Disconnect" to { viewModel.disconnectWebSocket() })
                                }
                                else -> emptyList()
                            }
                        )
                    }
                    
                    // Battery Optimization Card
                    item {
                        SystemStatusCard(
                            title = "Battery Optimization",
                            icon = Icons.Default.Info,
                            status = status.batteryOptimizationStatus.statusText,
                            statusColor = status.batteryOptimizationStatus.statusColor,
                            details = buildList {
                                add("Ignoring Battery Optimizations" to if (status.batteryOptimizationStatus.isIgnoringBatteryOptimizations) "Yes" else "No")
                                add("Power Save Mode" to status.batteryOptimizationStatus.powerSaveMode.name.replace("_", " "))
                                add("Can Request Exemption" to if (status.batteryOptimizationStatus.canRequestIgnoreBatteryOptimizations) "Yes" else "No")
                            },
                            actions = if (!status.batteryOptimizationStatus.isIgnoringBatteryOptimizations && 
                                         status.batteryOptimizationStatus.canRequestIgnoreBatteryOptimizations) {
                                listOf("Request Battery Exemption" to { viewModel.requestBatteryOptimization() })
                            } else emptyList()
                        )
                    }
                    
                    // Permissions Card
                    item {
                        SystemStatusCard(
                            title = "App Permissions",
                            icon = Icons.Default.Lock,
                            status = status.permissionStatus.statusText,
                            statusColor = status.permissionStatus.statusColor,
                            details = buildList {
                                add("All Required Permissions" to if (status.permissionStatus.hasRequiredPermissions) "Granted" else "Missing")
                                add("Accessibility Service" to if (status.permissionStatus.hasAccessibilityPermission) "Enabled" else "Disabled")
                                add("Notification Permission" to if (status.permissionStatus.hasNotificationPermission) "Granted" else "Not Granted")
                                add("Boot Permission" to if (status.permissionStatus.hasBootPermission) "Granted" else "Not Granted")
                                if (status.permissionStatus.missingPermissions.isNotEmpty()) {
                                    add("Missing Permissions" to status.permissionStatus.missingPermissions.joinToString(", "))
                                }
                            },
                            actions = if (!status.permissionStatus.hasAccessibilityPermission) {
                                listOf("Open Accessibility Settings" to { viewModel.openAccessibilitySettings() })
                            } else emptyList()
                        )
                    }
                    
                    // Device Info Card
                    item {
                        SystemStatusCard(
                            title = "Device Information",
                            icon = Icons.Default.Phone,
                            status = "${status.deviceInfo.manufacturer} ${status.deviceInfo.deviceModel}",
                            statusColor = SystemStatusColor.NEUTRAL,
                            details = buildList {
                                add("Android Version" to "${status.deviceInfo.androidVersion} (API ${status.deviceInfo.apiLevel})")
                                add("Manufacturer" to status.deviceInfo.manufacturer)
                                add("Model" to status.deviceInfo.deviceModel)
                                add("Build Type" to status.deviceInfo.buildType)
                                status.deviceInfo.kernelVersion?.let { kernel ->
                                    add("Kernel Version" to kernel)
                                }
                                if (status.deviceInfo.totalMemory > 0) {
                                    val totalMemoryMB = status.deviceInfo.totalMemory / (1024 * 1024)
                                    val availableMemoryMB = status.deviceInfo.availableMemory / (1024 * 1024)
                                    val usedMemoryMB = totalMemoryMB - availableMemoryMB
                                    add("Memory Usage" to "${usedMemoryMB}MB / ${totalMemoryMB}MB (${status.deviceInfo.memoryUsagePercentage.toInt()}%)")
                                }
                            }
                        )
                    }
                }
                
                // Error message
                uiState.errorMessage?.let { error ->
                    item {
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
        }
    }
}

@Composable
private fun SystemStatusCard(
    title: String,
    icon: ImageVector,
    status: String,
    statusColor: SystemStatusColor,
    details: List<Pair<String, String>>,
    actions: List<Pair<String, () -> Unit>> = emptyList()
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (statusColor) {
                SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                SystemStatusColor.WARNING -> MaterialTheme.colorScheme.secondaryContainer
                SystemStatusColor.ERROR -> MaterialTheme.colorScheme.errorContainer
                SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = when (statusColor) {
                        SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                        SystemStatusColor.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
                        SystemStatusColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                        SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (statusColor) {
                            SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                            SystemStatusColor.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
                            SystemStatusColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                            SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (statusColor) {
                            SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                            SystemStatusColor.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
                            SystemStatusColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                            SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                
                // Status indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = when (statusColor) {
                                SystemStatusColor.SUCCESS -> Color.Green
                                SystemStatusColor.WARNING -> Color(0xFFFF9800) // Orange
                                SystemStatusColor.ERROR -> Color.Red
                                SystemStatusColor.NEUTRAL -> Color.Gray
                            },
                            shape = CircleShape
                        )
                )
            }
            
            // Details
            if (details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                details.forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (statusColor) {
                                SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                SystemStatusColor.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                SystemStatusColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = when (statusColor) {
                                SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                                SystemStatusColor.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
                                SystemStatusColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                                SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            
            // Actions
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    actions.forEach { (actionText, action) ->
                        TextButton(onClick = action) {
                            Text(
                                text = actionText,
                                color = when (statusColor) {
                                    SystemStatusColor.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                                    SystemStatusColor.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
                                    SystemStatusColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                                    SystemStatusColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (actions.indexOf(actionText to action) < actions.size - 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }
}