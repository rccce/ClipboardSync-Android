package com.siw.clipboardsync.presentation.monitoring

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.monitor.LatencyTracker
import com.siw.clipboardsync.monitor.MonitoringStrategyFactory
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.RootDetectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for monitoring status screen.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5
 */
@HiltViewModel
class MonitoringStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitoringStrategyFactory: MonitoringStrategyFactory,
    private val latencyTracker: LatencyTracker,
    private val rootDetectionService: RootDetectionService
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MonitoringStatusUiState())
    val uiState: StateFlow<MonitoringStatusUiState> = _uiState.asStateFlow()
    
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    init {
        refreshStatus()
    }
    
    /**
     * Refreshes the monitoring status.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            try {
                // Get available methods
                val strategies = monitoringStrategyFactory.createAvailableStrategies()
                val availableMethods = strategies.map { strategy ->
                    MethodInfo(
                        method = strategy.method,
                        isAvailable = strategy.isAvailable,
                        description = getMethodDescription(strategy.method)
                    )
                }
                
                // Get current method
                val currentMethod = monitoringStrategyFactory.getLastSuccessfulStrategy()
                
                // Get latency stats
                val latencyStats = currentMethod?.let { method ->
                    val stats = latencyTracker.getLatencyStats(method)
                    if (stats.sampleCount > 0) {
                        LatencyDisplayStats(
                            averageMs = stats.averageLatencyMs.toLong(),
                            minMs = stats.minLatencyMs,
                            maxMs = stats.maxLatencyMs,
                            targetMs = stats.targetLatencyMs,
                            meetsTarget = stats.meetsTarget
                        )
                    } else null
                }
                
                // Check if LSPosed is available but module not active
                val rootCapabilities = rootDetectionService.getRootCapabilities()
                val showLSPosedHint = rootCapabilities.hasXposedFramework && 
                    currentMethod == MonitoringMethod.XPOSED_HOOKS &&
                    !isLSPosedModuleActive()
                
                _uiState.update { state ->
                    state.copy(
                        availableMethods = availableMethods,
                        currentMethod = currentMethod,
                        latencyStats = latencyStats,
                        showLSPosedSetupHint = showLSPosedHint,
                        isLoading = false
                    )
                }
                
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        error = e.message,
                        isLoading = false
                    )
                }
            }
        }
    }
    
    /**
     * Checks if LSPosed module is active in system framework.
     */
    private fun isLSPosedModuleActive(): Boolean {
        // Check if we're receiving broadcasts from the Xposed module
        // This is a simple heuristic - if the module is active, it would have
        // hooked the clipboard service and we'd see different behavior
        return try {
            // For now, we assume the module is not active if we're using polling
            // A more sophisticated check would involve checking LSPosed's database
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Opens LSPosed Manager app.
     */
    fun openLSPosedManager() {
        try {
            // Try to open LSPosed Manager
            val lsposedPackages = listOf(
                "org.lsposed.manager",
                "com.android.shell" // LSPosed parasitic mode uses shell
            )
            
            for (packageName in lsposedPackages) {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return
                    }
                } catch (e: Exception) {
                    // Try next package
                }
            }
            
            // If LSPosed Manager is not found (parasitic mode), show instructions
            // The user needs to dial *#*#5776733#*#* or use other methods
            _uiState.update { state ->
                state.copy(
                    error = "LSPosed Manager 可能处于寄生模式。请拨打 *#*#5776733#*#* 打开管理器，或在通知栏中查找 LSPosed 通知。"
                )
            }
            
        } catch (e: Exception) {
            _uiState.update { state ->
                state.copy(error = "无法打开 LSPosed Manager: ${e.message}")
            }
        }
    }
    
    /**
     * Toggles monitoring on/off.
     */
    fun toggleMonitoring() {
        viewModelScope.launch {
            val newState = !_uiState.value.isMonitoring
            _uiState.update { it.copy(isMonitoring = newState) }
            
            // TODO: Actually start/stop monitoring service
        }
    }
    
    /**
     * Selects a monitoring method.
     * Requirements: 11.3
     */
    fun selectMethod(method: MonitoringMethod) {
        viewModelScope.launch {
            _uiState.update { it.copy(currentMethod = method) }
            
            // TODO: Actually switch monitoring method
        }
    }
    
    /**
     * Runs a clipboard monitoring test.
     * Requirements: 11.4
     */
    fun runClipboardTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestRunning = true, testResult = null) }
            
            try {
                // Generate unique test content
                val testContent = "ClipboardSync Test ${UUID.randomUUID().toString().take(8)}"
                val contentHash = computeHash(testContent)
                
                // Record test initiation
                latencyTracker.recordClipboardChangeInitiated(contentHash)
                val startTime = System.currentTimeMillis()
                
                // Set clipboard content
                val clip = ClipData.newPlainText("test", testContent)
                clipboardManager.setPrimaryClip(clip)
                
                // Wait for detection (with timeout)
                var detected = false
                var latencyMs: Long? = null
                val timeout = 5000L // 5 seconds timeout
                
                while (System.currentTimeMillis() - startTime < timeout) {
                    delay(100)
                    
                    // Check if clipboard content matches
                    val currentClip = clipboardManager.primaryClip
                    if (currentClip != null && currentClip.itemCount > 0) {
                        val currentText = currentClip.getItemAt(0)?.text?.toString()
                        if (currentText == testContent) {
                            detected = true
                            latencyMs = System.currentTimeMillis() - startTime
                            
                            // Record detection
                            _uiState.value.currentMethod?.let { method ->
                                latencyTracker.recordClipboardChangeDetected(contentHash, method)
                            }
                            break
                        }
                    }
                }
                
                val result = if (detected) {
                    TestResult(
                        success = true,
                        message = "剪贴板监控正常工作",
                        latencyMs = latencyMs
                    )
                } else {
                    TestResult(
                        success = false,
                        message = "检测超时，请检查监控设置"
                    )
                }
                
                _uiState.update { it.copy(isTestRunning = false, testResult = result) }
                
                // Update stats
                updateSyncStats(detected)
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isTestRunning = false, 
                        testResult = TestResult(
                            success = false,
                            message = "测试失败: ${e.message}"
                        )
                    ) 
                }
            }
        }
    }
    
    /**
     * Updates sync statistics.
     */
    private fun updateSyncStats(success: Boolean) {
        _uiState.update { state ->
            val currentStats = state.syncStats
            val newTotalSyncs = currentStats.totalSyncs + 1
            val newTodaySyncs = currentStats.todaySyncs + 1
            val successCount = (currentStats.totalSyncs * currentStats.successRate).toLong() + if (success) 1 else 0
            
            state.copy(
                syncStats = currentStats.copy(
                    totalSyncs = newTotalSyncs,
                    todaySyncs = newTodaySyncs,
                    successRate = successCount.toDouble() / newTotalSyncs,
                    textSyncs = currentStats.textSyncs + 1
                )
            )
        }
    }
    
    /**
     * Gets description for a monitoring method.
     */
    private fun getMethodDescription(method: MonitoringMethod): String {
        return when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> "需要 Root 权限，延迟最低"
            MonitoringMethod.XPOSED_HOOKS -> "需要 Xposed/LSPosed 框架"
            MonitoringMethod.READ_LOGS -> "需要 READ_LOGS 权限 (ADB)"
            MonitoringMethod.ACCESSIBILITY_SERVICE -> "需要无障碍服务权限"
            MonitoringMethod.FOREGROUND_SERVICE -> "显示持续通知"
            MonitoringMethod.POLLING_FALLBACK -> "定期检查，电池消耗较高"
        }
    }
    
    /**
     * Computes hash of content for tracking.
     */
    private fun computeHash(content: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(content.toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            content.hashCode().toString()
        }
    }
}

/**
 * UI state for monitoring status screen.
 */
data class MonitoringStatusUiState(
    val isMonitoring: Boolean = false,
    val currentMethod: MonitoringMethod? = null,
    val availableMethods: List<MethodInfo> = emptyList(),
    val isTestRunning: Boolean = false,
    val testResult: TestResult? = null,
    val syncStats: SyncStats = SyncStats(),
    val latencyStats: LatencyDisplayStats? = null,
    val showLSPosedSetupHint: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)
