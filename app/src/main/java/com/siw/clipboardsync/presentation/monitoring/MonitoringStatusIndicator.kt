package com.siw.clipboardsync.presentation.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siw.clipboardsync.monitor.model.MonitoringMethod

@Composable
fun MonitoringStatusIndicator(
    isMonitoring: Boolean,
    currentMethod: MonitoringMethod?,
    hasPermissions: Boolean,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !hasPermissions -> MaterialTheme.colorScheme.errorContainer
                isMonitoring -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            !hasPermissions -> {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            isMonitoring -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Animated pulsing effect for active monitoring
                                    AnimatedStatusDot(
                                        color = MaterialTheme.colorScheme.primary,
                                        isAnimated = true
                                    )
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AnimatedStatusDot(
                                        color = MaterialTheme.colorScheme.outline,
                                        isAnimated = false
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = when {
                            !hasPermissions -> "Monitoring - Permissions Required"
                            isMonitoring -> "Advanced Monitoring - Active"
                            else -> "Advanced Monitoring - Inactive"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            !hasPermissions -> MaterialTheme.colorScheme.onErrorContainer
                            isMonitoring -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    if (currentMethod != null) {
                        Text(
                            text = "Method: ${getMethodDisplayName(currentMethod)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                !hasPermissions -> MaterialTheme.colorScheme.onErrorContainer
                                isMonitoring -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    } else if (!hasPermissions) {
                        Text(
                            text = "Grant permissions to enable advanced monitoring",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Monitoring Settings",
                    tint = when {
                        !hasPermissions -> MaterialTheme.colorScheme.onErrorContainer
                        isMonitoring -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun AnimatedStatusDot(
    color: Color,
    isAnimated: Boolean,
    modifier: Modifier = Modifier
) {
    if (isAnimated) {
        // Simple pulsing animation
        var alpha by remember { mutableStateOf(1f) }
        
        LaunchedEffect(Unit) {
            while (true) {
                alpha = 0.3f
                kotlinx.coroutines.delay(1000)
                alpha = 1f
                kotlinx.coroutines.delay(1000)
            }
        }
        
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            ) {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = size.minDimension / 2
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(CircleShape)
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2
                )
            }
        }
    }
}

@Composable
fun CompactMonitoringStatus(
    isMonitoring: Boolean,
    currentMethod: MonitoringMethod?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
        ) {
            AnimatedStatusDot(
                color = if (isMonitoring) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.outline,
                isAnimated = isMonitoring
            )
        }
        
        Spacer(modifier = Modifier.width(6.dp))
        
        Text(
            text = if (isMonitoring) {
                currentMethod?.let { "Active (${getMethodDisplayName(it)})" } ?: "Active"
            } else {
                "Inactive"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isMonitoring) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MonitoringMethodChip(
    method: MonitoringMethod,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        onClick = onClick,
        label = { 
            Text(
                text = getMethodDisplayName(method),
                style = MaterialTheme.typography.bodySmall
            )
        },
        selected = isActive,
        leadingIcon = if (isActive) {
            {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else null,
        modifier = modifier
    )
}

private fun getMethodDisplayName(method: MonitoringMethod): String {
    return when (method) {
        MonitoringMethod.SYSTEM_HOOKS -> "System Hooks"
        MonitoringMethod.XPOSED_HOOKS -> "Xposed"
        MonitoringMethod.READ_LOGS -> "Logcat"
        MonitoringMethod.ACCESSIBILITY_SERVICE -> "Accessibility"
        MonitoringMethod.FOREGROUND_SERVICE -> "Foreground"
        MonitoringMethod.POLLING_FALLBACK -> "Polling"
    }
}