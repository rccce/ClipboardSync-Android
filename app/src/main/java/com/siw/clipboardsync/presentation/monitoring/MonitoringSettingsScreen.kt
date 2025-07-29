package com.siw.clipboardsync.presentation.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.siw.clipboardsync.monitor.model.MonitoringMethod

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MonitoringSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monitoring Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current Status Section
            item {
                CurrentStatusCard(
                    currentMethod = uiState.currentMethod,
                    isMonitoring = uiState.isMonitoring,
                    onToggleMonitoring = viewModel::toggleMonitoring
                )
            }
            
            // Method Preferences Section
            item {
                MethodPreferencesCard(
                    preferredMethod = uiState.preferredMethod,
                    availableMethods = uiState.availableMethods,
                    onMethodSelected = viewModel::setPreferredMethod
                )
            }
            
            // Method Configuration Section
            item {
                MethodConfigurationCard(
                    enabledMethods = uiState.enabledMethods,
                    onMethodToggled = viewModel::toggleMethod
                )
            }
            
            // Advanced Settings Section
            item {
                AdvancedSettingsCard(
                    autoFallback = uiState.autoFallback,
                    enableNotifications = uiState.enableNotifications,
                    enableErrorRecovery = uiState.enableErrorRecovery,
                    onAutoFallbackToggled = viewModel::toggleAutoFallback,
                    onNotificationsToggled = viewModel::toggleNotifications,
                    onErrorRecoveryToggled = viewModel::toggleErrorRecovery
                )
            }
            
            // Performance Metrics Section
            item {
                PerformanceMetricsCard(
                    cpuUsage = uiState.cpuUsage,
                    memoryUsage = uiState.memoryUsage,
                    batteryImpact = uiState.batteryImpact,
                    onRefreshMetrics = viewModel::refreshMetrics
                )
            }
            
            // Troubleshooting Section
            item {
                TroubleshootingCard(
                    lastError = uiState.lastError,
                    onRunDiagnostics = viewModel::runDiagnostics,
                    onClearErrors = viewModel::clearErrors
                )
            }
        }
    }
}

@Composable
private fun CurrentStatusCard(
    currentMethod: MonitoringMethod?,
    isMonitoring: Boolean,
    onToggleMonitoring: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMonitoring) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitoring Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isMonitoring) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isMonitoring) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Text(
                            text = if (isMonitoring) "Active" else "Inactive",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isMonitoring) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                    
                    if (currentMethod != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Method: ${getMethodDisplayName(currentMethod)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Switch(
                    checked = isMonitoring,
                    onCheckedChange = { onToggleMonitoring() }
                )
            }
        }
    }
}

@Composable
private fun MethodPreferencesCard(
    preferredMethod: MonitoringMethod?,
    availableMethods: List<MonitoringMethod>,
    onMethodSelected: (MonitoringMethod?) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Preferred Method",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Choose your preferred monitoring method. The system will try this method first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Auto option
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = preferredMethod == null,
                    onClick = { onMethodSelected(null) }
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column {
                    Text(
                        text = "Automatic",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Let the system choose the best method",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Available methods
            availableMethods.forEach { method ->
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = preferredMethod == method,
                        onClick = { onMethodSelected(method) }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column {
                        Text(
                            text = getMethodDisplayName(method),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = getMethodDescription(method),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodConfigurationCard(
    enabledMethods: Map<MonitoringMethod, Boolean>,
    onMethodToggled: (MonitoringMethod, Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Available Methods",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Enable or disable specific monitoring methods.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            enabledMethods.forEach { (method, enabled) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getMethodDisplayName(method),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = getMethodDescription(method),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onMethodToggled(method, it) }
                    )
                }
                
                if (method != enabledMethods.keys.last()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettingsCard(
    autoFallback: Boolean,
    enableNotifications: Boolean,
    enableErrorRecovery: Boolean,
    onAutoFallbackToggled: (Boolean) -> Unit,
    onNotificationsToggled: (Boolean) -> Unit,
    onErrorRecoveryToggled: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Advanced Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Auto Fallback
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto Fallback",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Automatically try alternative methods if preferred method fails",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Switch(
                    checked = autoFallback,
                    onCheckedChange = onAutoFallbackToggled
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Notifications
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Error Notifications",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Show notifications when monitoring issues occur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Switch(
                    checked = enableNotifications,
                    onCheckedChange = onNotificationsToggled
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Error Recovery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto Recovery",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Automatically attempt to recover from monitoring errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Switch(
                    checked = enableErrorRecovery,
                    onCheckedChange = onErrorRecoveryToggled
                )
            }
        }
    }
}

@Composable
private fun PerformanceMetricsCard(
    cpuUsage: Double,
    memoryUsage: Long,
    batteryImpact: String,
    onRefreshMetrics: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Performance Metrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = onRefreshMetrics) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // CPU Usage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "CPU Usage",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${String.format("%.2f", cpuUsage)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (cpuUsage > 1.0) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Memory Usage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Memory Usage",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${memoryUsage / 1024 / 1024} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Battery Impact
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Battery Impact",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = batteryImpact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (batteryImpact.lowercase()) {
                        "low" -> MaterialTheme.colorScheme.primary
                        "medium" -> MaterialTheme.colorScheme.tertiary
                        "high" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun TroubleshootingCard(
    lastError: String?,
    onRunDiagnostics: () -> Unit,
    onClearErrors: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Troubleshooting",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (lastError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Text(
                                text = "Last Error",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRunDiagnostics,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run Diagnostics")
                }
                
                if (lastError != null) {
                    OutlinedButton(
                        onClick = onClearErrors,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Errors")
                    }
                }
            }
        }
    }
}

private fun getMethodDisplayName(method: MonitoringMethod): String {
    return when (method) {
        MonitoringMethod.SYSTEM_HOOKS -> "System Hooks"
        MonitoringMethod.XPOSED_HOOKS -> "Xposed Framework"
        MonitoringMethod.ACCESSIBILITY_SERVICE -> "Accessibility Service"
        MonitoringMethod.FOREGROUND_SERVICE -> "Foreground Service"
        MonitoringMethod.POLLING_FALLBACK -> "Polling Fallback"
    }
}

private fun getMethodDescription(method: MonitoringMethod): String {
    return when (method) {
        MonitoringMethod.SYSTEM_HOOKS -> "Direct system-level clipboard hooks (requires root)"
        MonitoringMethod.XPOSED_HOOKS -> "Xposed/LSPosed framework integration (requires root)"
        MonitoringMethod.ACCESSIBILITY_SERVICE -> "Uses accessibility service for clipboard access"
        MonitoringMethod.FOREGROUND_SERVICE -> "Persistent foreground service monitoring"
        MonitoringMethod.POLLING_FALLBACK -> "Periodic clipboard checking (battery intensive)"
    }
}