package com.siw.clipboardsync.presentation.monitoring

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PermissionRequestDialog(
    permissionType: PermissionType,
    onDismiss: () -> Unit,
    onGranted: () -> Unit
) {
    val context = LocalContext.current
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (permissionType) {
                        PermissionType.ACCESSIBILITY -> Icons.Default.Settings
                        PermissionType.NOTIFICATION -> Icons.Default.Notifications
                        PermissionType.OVERLAY -> Icons.Default.Info
                        PermissionType.READ_LOGS -> Icons.Default.List
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = when (permissionType) {
                        PermissionType.ACCESSIBILITY -> "Accessibility Permission Required"
                        PermissionType.NOTIFICATION -> "Notification Permission Required"
                        PermissionType.OVERLAY -> "Overlay Permission Required"
                        PermissionType.READ_LOGS -> "READ_LOGS Permission Required"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = when (permissionType) {
                        PermissionType.ACCESSIBILITY -> "ClipboardSync needs accessibility permission to monitor clipboard changes in the background. This enables reliable clipboard synchronization without draining your battery."
                        PermissionType.NOTIFICATION -> "ClipboardSync needs notification permission to show you important updates about clipboard synchronization and any issues that may occur."
                        PermissionType.OVERLAY -> "ClipboardSync needs overlay permission to display clipboard content previews and quick actions over other apps."
                        PermissionType.READ_LOGS -> "ClipboardSync can use READ_LOGS permission to detect clipboard changes by monitoring system logs. This requires granting the permission via ADB command on a computer."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Permission steps
                PermissionSteps(permissionType = permissionType)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    
                    Button(
                        onClick = {
                            when (permissionType) {
                                PermissionType.ACCESSIBILITY -> {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                }
                                PermissionType.NOTIFICATION -> {
                                    val intent = Intent().apply {
                                        action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                                PermissionType.OVERLAY -> {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                }
                                PermissionType.READ_LOGS -> {
                                    // READ_LOGS cannot be granted via settings, show instructions only
                                    // The dialog already shows the ADB command instructions
                                }
                            }
                            onGranted()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (permissionType == PermissionType.READ_LOGS) "Got It" else "Open Settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSteps(permissionType: PermissionType) {
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
                text = "How to grant permission:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            when (permissionType) {
                PermissionType.ACCESSIBILITY -> {
                    PermissionStep(
                        step = "1",
                        description = "Tap 'Open Settings' below"
                    )
                    PermissionStep(
                        step = "2",
                        description = "Find 'ClipboardSync' in the list"
                    )
                    PermissionStep(
                        step = "3",
                        description = "Toggle the switch to enable the service"
                    )
                    PermissionStep(
                        step = "4",
                        description = "Confirm by tapping 'OK' in the dialog"
                    )
                }
                PermissionType.NOTIFICATION -> {
                    PermissionStep(
                        step = "1",
                        description = "Tap 'Open Settings' below"
                    )
                    PermissionStep(
                        step = "2",
                        description = "Enable 'Allow notifications'"
                    )
                    PermissionStep(
                        step = "3",
                        description = "Optionally customize notification categories"
                    )
                }
                PermissionType.OVERLAY -> {
                    PermissionStep(
                        step = "1",
                        description = "Tap 'Open Settings' below"
                    )
                    PermissionStep(
                        step = "2",
                        description = "Find 'ClipboardSync' in the app list"
                    )
                    PermissionStep(
                        step = "3",
                        description = "Toggle 'Allow display over other apps'"
                    )
                }
                PermissionType.READ_LOGS -> {
                    PermissionStep(
                        step = "1",
                        description = "Enable USB debugging on your device"
                    )
                    PermissionStep(
                        step = "2",
                        description = "Connect device to computer with ADB"
                    )
                    PermissionStep(
                        step = "3",
                        description = "Run: adb shell pm grant com.siw.clipboardsync android.permission.READ_LOGS"
                    )
                    PermissionStep(
                        step = "4",
                        description = "Return to app and verify permission status"
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStep(
    step: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PermissionStatusCard(
    permissionType: PermissionType,
    isGranted: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
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
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isGranted) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = when (permissionType) {
                            PermissionType.ACCESSIBILITY -> "Accessibility Service"
                            PermissionType.NOTIFICATION -> "Notifications"
                            PermissionType.OVERLAY -> "Display Over Apps"
                            PermissionType.READ_LOGS -> "Read System Logs"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isGranted) 
                            MaterialTheme.colorScheme.onPrimaryContainer 
                        else 
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    
                    Text(
                        text = if (isGranted) "Granted" else "Required",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isGranted) 
                            MaterialTheme.colorScheme.onPrimaryContainer 
                        else 
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            if (!isGranted) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Grant")
                }
            }
        }
    }
}

enum class PermissionType {
    ACCESSIBILITY,
    NOTIFICATION,
    OVERLAY,
    READ_LOGS
}