package com.siw.clipboardsync.presentation.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.siw.clipboardsync.utils.RootUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel()
) {
    val statusState by viewModel.statusState.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("System Status") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        
        // Status Content
        StatusContent(
            statusState = statusState,
            onRefresh = { viewModel.refreshStatus() },
            onForceRefresh = { viewModel.forceRefreshWithDebug() }
        )
    }
}

@Composable
private fun StatusContent(
    statusState: StatusViewModel.StatusState,
    onRefresh: () -> Unit,
    onForceRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Error Display
        statusState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Error: $error",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Root Access Status
        StatusCard(
            title = "Root Access",
            status = statusState.capabilities?.isRooted ?: false,
            isLoading = statusState.isLoading,
            icon = Icons.Default.Lock,
            description = if (statusState.capabilities?.isRooted == true) 
                "Root access granted - enhanced monitoring available" 
            else 
                "No root access - using standard monitoring"
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Monitoring Mode Status
        StatusCard(
            title = "Monitoring Mode",
            status = statusState.capabilities?.isRooted ?: false,
            isLoading = statusState.isLoading,
            icon = Icons.Default.Settings,
            description = if (statusState.capabilities?.isRooted == true) 
                "Enhanced mode - faster polling and better background access" 
            else 
                "Standard mode - basic clipboard monitoring",
            additionalInfo = "Polling interval: ${statusState.capabilities?.recommendedPollingInterval ?: "Unknown"}ms"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Root Information Card - Show when device is not rooted
        if (statusState.capabilities?.isRooted == false) {
            RootInformationCard()
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Debug Information Card
        if (statusState.capabilities != null) {
            DebugInfoCard(statusState.capabilities!!)
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Performance Info
        PerformanceInfoCard(statusState.capabilities)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Refresh Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
                enabled = !statusState.isLoading
            ) {
                if (statusState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Refresh")
            }
            
            OutlinedButton(
                onClick = onForceRefresh,
                modifier = Modifier.weight(1f),
                enabled = !statusState.isLoading
            ) {
                Text("Debug Refresh")
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    status: Boolean,
    isLoading: Boolean,
    icon: ImageVector,
    description: String,
    additionalInfo: String? = null,
    isWarning: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isWarning -> MaterialTheme.colorScheme.tertiaryContainer
                status -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = when {
                    isWarning -> MaterialTheme.colorScheme.onTertiaryContainer
                    status -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isWarning -> MaterialTheme.colorScheme.onTertiaryContainer
                        status -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isWarning -> MaterialTheme.colorScheme.onTertiaryContainer
                        status -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                
                additionalInfo?.let { info ->
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isWarning -> MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            status -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        }
                    )
                }
            }
            
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = if (status) Icons.Default.CheckCircle else Icons.Default.Close,
                    contentDescription = if (status) "Active" else "Inactive",
                    tint = if (status) Color.Green else Color.Red,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun RootInformationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Enhanced Monitoring",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "For enhanced clipboard monitoring with faster polling and better background access, root access is required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Benefits of root access:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            
            val benefits = listOf(
                "• Faster clipboard polling (1s vs 5s)",
                "• Better background access",
                "• Bypasses Android 10+ restrictions",
                "• More reliable synchronization"
            )
            
            benefits.forEach { benefit ->
                Text(
                    text = benefit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfigurationGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LSPosed Configuration Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "To enable real-time clipboard monitoring:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val steps = listOf(
                "1. Open LSPosed Manager app",
                "2. Go to 'Modules' tab",
                "3. Find 'ClipboardSync' and enable it",
                "4. Tap on ClipboardSync module",
                "5. Check 'System Framework (android)' - NOT individual apps",
                "6. Reboot your device",
                "7. Return here and refresh to verify activation"
            )
            
            steps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "IMPORTANT: Select 'System Framework (android)' as the scope, not individual apps!",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun DebugInfoCard(capabilities: RootUtils.ClipboardCapabilities) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Debug Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val debugInfo = listOf(
                "Root Access" to capabilities.isRooted.toString(),
                "Optimization Level" to capabilities.getOptimizationLevel().name,
                "Background Access" to capabilities.canBypassAndroid10Restrictions.toString(),
                "Polling Interval" to "${capabilities.recommendedPollingInterval}ms"
            )
            
            debugInfo.forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceInfoCard(capabilities: RootUtils.ClipboardCapabilities?) {
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Performance Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            capabilities?.let { caps ->
                Text(
                    text = "Current Level: ${caps.getOptimizationLevel().name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val performanceDetails = when (caps.getOptimizationLevel()) {
                    RootUtils.OptimizationLevel.HIGH -> listOf(
                        "• Fast clipboard detection (< 1s)",
                        "• Low battery usage",
                        "• Enhanced monitoring",
                        "• Root-level access"
                    )
                    RootUtils.OptimizationLevel.STANDARD -> listOf(
                        "• Polling-based detection (1-5s delay)",
                        "• Moderate battery usage",
                        "• Limited background access",
                        "• Standard Android APIs"
                    )
                }
                
                performanceDetails.forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}