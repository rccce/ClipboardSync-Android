package com.siw.clipboardsync.presentation.monitoring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siw.clipboardsync.monitor.model.MonitoringMethod

/**
 * Screen for displaying and configuring clipboard monitoring status.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringStatusScreen(
    viewModel: MonitoringStatusViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("监控状态") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
            // Current Status Card
            item {
                StatusCurrentStatusCard(
                    isMonitoring = uiState.isMonitoring,
                    currentMethod = uiState.currentMethod,
                    onToggleMonitoring = { viewModel.toggleMonitoring() }
                )
            }
            
            // LSPosed Setup Card (show when Xposed is detected but module not active)
            if (uiState.showLSPosedSetupHint) {
                item {
                    LSPosedSetupCard(
                        onOpenLSPosed = { viewModel.openLSPosedManager() }
                    )
                }
            }
            
            // Method Selection Card
            item {
                MethodSelectionCard(
                    availableMethods = uiState.availableMethods,
                    currentMethod = uiState.currentMethod,
                    onMethodSelected = { viewModel.selectMethod(it) }
                )
            }
            
            // Test Function Card
            item {
                TestFunctionCard(
                    isTestRunning = uiState.isTestRunning,
                    testResult = uiState.testResult,
                    onRunTest = { viewModel.runClipboardTest() }
                )
            }
            
            // Statistics Card
            item {
                StatisticsCard(
                    stats = uiState.syncStats
                )
            }
            
            // Latency Card
            item {
                LatencyCard(
                    latencyStats = uiState.latencyStats
                )
            }
        }
    }
}

/**
 * Card showing current monitoring status.
 * Requirements: 11.1, 11.2
 */
@Composable
private fun StatusCurrentStatusCard(
    isMonitoring: Boolean,
    currentMethod: MonitoringMethod?,
    onToggleMonitoring: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isMonitoring) Color.Green else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isMonitoring) "监控中" else "已停止",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Switch(
                    checked = isMonitoring,
                    onCheckedChange = { onToggleMonitoring() }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            currentMethod?.let { method ->
                Text(
                    text = "当前方法: ${getStatusMethodDisplayName(method)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card for selecting monitoring method.
 * Requirements: 11.3
 */
@Composable
fun MethodSelectionCard(
    availableMethods: List<MethodInfo>,
    currentMethod: MonitoringMethod?,
    onMethodSelected: (MonitoringMethod) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "监控方法",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            availableMethods.forEach { methodInfo ->
                MethodItem(
                    methodInfo = methodInfo,
                    isSelected = methodInfo.method == currentMethod,
                    onSelect = { onMethodSelected(methodInfo.method) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun MethodItem(
    methodInfo: MethodInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = methodInfo.isAvailable) { onSelect() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = getMethodIcon(methodInfo.method),
                contentDescription = null,
                tint = if (methodInfo.isAvailable) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = getStatusMethodDisplayName(methodInfo.method),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (methodInfo.isAvailable) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = methodInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        if (!methodInfo.isAvailable) {
            Text(
                text = "不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Card for testing clipboard monitoring.
 * Requirements: 11.4
 */
@Composable
fun TestFunctionCard(
    isTestRunning: Boolean,
    testResult: TestResult?,
    onRunTest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "监控测试",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "测试剪贴板监控是否正常工作",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onRunTest,
                enabled = !isTestRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("测试中...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("运行测试")
                }
            }
            
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (result.success) Color.Green.copy(alpha = 0.1f)
                            else Color.Red.copy(alpha = 0.1f)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (result.success) Color.Green else Color.Red
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (result.success) "测试通过" else "测试失败",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (result.latencyMs != null) {
                            Text(
                                text = "检测延迟: ${result.latencyMs}ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card showing sync statistics.
 * Requirements: 11.5
 */
@Composable
fun StatisticsCard(
    stats: SyncStats
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "同步统计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "总同步",
                    value = stats.totalSyncs.toString()
                )
                StatItem(
                    label = "今日同步",
                    value = stats.todaySyncs.toString()
                )
                StatItem(
                    label = "成功率",
                    value = "${(stats.successRate * 100).toInt()}%"
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "文本",
                    value = stats.textSyncs.toString()
                )
                StatItem(
                    label = "图片",
                    value = stats.imageSyncs.toString()
                )
                StatItem(
                    label = "文件",
                    value = stats.fileSyncs.toString()
                )
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Card showing latency statistics.
 */
@Composable
fun LatencyCard(
    latencyStats: LatencyDisplayStats?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "检测延迟",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (latencyStats == null) {
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        label = "平均",
                        value = "${latencyStats.averageMs}ms"
                    )
                    StatItem(
                        label = "最小",
                        value = "${latencyStats.minMs}ms"
                    )
                    StatItem(
                        label = "最大",
                        value = "${latencyStats.maxMs}ms"
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "目标: ${latencyStats.targetMs}ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (latencyStats.meetsTarget) 
                            Icons.Default.CheckCircle 
                        else 
                            Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (latencyStats.meetsTarget) Color.Green else Color.Yellow,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Helper functions
private fun getStatusMethodDisplayName(method: MonitoringMethod): String {
    return when (method) {
        MonitoringMethod.SYSTEM_HOOKS -> "系统钩子 (Root)"
        MonitoringMethod.XPOSED_HOOKS -> "Xposed 框架"
        MonitoringMethod.READ_LOGS -> "日志读取"
        MonitoringMethod.ACCESSIBILITY_SERVICE -> "无障碍服务"
        MonitoringMethod.FOREGROUND_SERVICE -> "前台服务"
        MonitoringMethod.POLLING_FALLBACK -> "轮询模式"
    }
}

private fun getMethodIcon(method: MonitoringMethod): ImageVector {
    return when (method) {
        MonitoringMethod.SYSTEM_HOOKS -> Icons.Default.Lock
        MonitoringMethod.XPOSED_HOOKS -> Icons.Default.Build
        MonitoringMethod.READ_LOGS -> Icons.Default.List
        MonitoringMethod.ACCESSIBILITY_SERVICE -> Icons.Default.Person
        MonitoringMethod.FOREGROUND_SERVICE -> Icons.Default.Notifications
        MonitoringMethod.POLLING_FALLBACK -> Icons.Default.Refresh
    }
}

// Data classes for UI state
data class MethodInfo(
    val method: MonitoringMethod,
    val isAvailable: Boolean,
    val description: String
)

data class TestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long? = null
)

data class SyncStats(
    val totalSyncs: Long = 0,
    val todaySyncs: Long = 0,
    val successRate: Double = 1.0,
    val textSyncs: Long = 0,
    val imageSyncs: Long = 0,
    val fileSyncs: Long = 0
)

data class LatencyDisplayStats(
    val averageMs: Long,
    val minMs: Long,
    val maxMs: Long,
    val targetMs: Long,
    val meetsTarget: Boolean
)

/**
 * Card showing LSPosed setup instructions.
 */
@Composable
fun LSPosedSetupCard(
    onOpenLSPosed: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LSPosed 模块设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "检测到 LSPosed 框架，但模块尚未启用。请按以下步骤操作：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = "1. 打开 LSPosed Manager",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "2. 点击「模块」标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "3. 找到「Clipboard Sync」并启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "4. 在作用域中勾选「系统框架」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "5. 重启设备使模块生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onOpenLSPosed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打开 LSPosed Manager")
            }
        }
    }
}
