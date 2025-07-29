package com.siw.clipboardsync.integration

import android.content.Context
import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
import com.siw.clipboardsync.monitor.migration.MonitoringMigrationManager
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.websocket.WebSocketClient
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for advanced monitoring system with existing ClipboardSync architecture.
 * Tests the complete flow from clipboard detection to sync completion.
 */
class AdvancedMonitoringIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var clipboardRepository: ClipboardRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var clipboardMonitorManager: ClipboardMonitorManager
    private lateinit var migrationManager: MonitoringMigrationManager
    private lateinit var clipboardSyncManager: ClipboardSyncManager
    
    private val testScope = TestScope()
    
    @Before
    fun setup() {
        // Mock dependencies
        context = mockk(relaxed = true)
        webSocketClient = mockk(relaxed = true)
        clipboardRepository = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        clipboardMonitorManager = mockk(relaxed = true)
        migrationManager = mockk(relaxed = true)
        
        // Setup mock behaviors
        every { authRepository.getDeviceId() } returns "test-device-id"
        coEvery { authRepository.getAccessToken() } returns "test-access-token"
        coEvery { authRepository.getCurrentUser() } returns mockk {
            every { id } returns "test-user-id"
        }
        
        every { webSocketClient.connectionStatus } returns MutableStateFlow(WebSocketClient.ConnectionStatus.CONNECTED)
        every { webSocketClient.clipboardUpdates } returns MutableStateFlow(mockk())
        every { webSocketClient.isConnected() } returns true
        
        every { clipboardMonitorManager.currentMethod } returns MutableStateFlow(MonitoringMethod.SYSTEM_HOOKS)
        every { clipboardMonitorManager.isMonitoring } returns MutableStateFlow(true)
        every { clipboardMonitorManager.getMonitoringStatus() } returns ClipboardMonitorManager.MonitoringStatus(
            isMonitoring = true,
            currentMethod = MonitoringMethod.SYSTEM_HOOKS,
            currentStrategy = mockk(),
            availableStrategies = listOf(mockk()),
            fallbackIndex = 0
        )
        
        coEvery { migrationManager.isMigrationCompleted() } returns true
        
        // Create ClipboardSyncManager instance
        clipboardSyncManager = ClipboardSyncManager(
            context = context,
            webSocketClient = webSocketClient,
            clipboardRepository = clipboardRepository,
            authRepository = authRepository,
            clipboardMonitorManager = clipboardMonitorManager,
            migrationManager = migrationManager
        )
    }
    
    @After
    fun tearDown() {
        testScope.cancel()
    }
    
    @Test
    fun `test advanced monitoring initialization and integration`() = testScope.runTest {
        // When: Initialize ClipboardSyncManager
        clipboardSyncManager.initialize()
        
        // Then: Advanced monitoring should be initialized
        coVerify { clipboardMonitorManager.initialize() }
        
        // And: Migration should be checked
        coVerify { migrationManager.isMigrationCompleted() }
        
        // And: Monitoring state should be observed
        assertTrue(clipboardSyncManager.isAdvancedMonitoringAvailable())
    }
    
    @Test
    fun `test advanced monitoring enables successfully`() = testScope.runTest {
        // Given: ClipboardSyncManager is initialized
        clipboardSyncManager.initialize()
        
        // When: Enable advanced monitoring
        val result = clipboardSyncManager.enableAdvancedMonitoring()
        
        // Then: Should succeed
        assertTrue(result)
        
        // And: Should start monitoring with ClipboardSyncManager as listener
        coVerify { clipboardMonitorManager.startMonitoring(clipboardSyncManager) }
    }
    
    @Test
    fun `test clipboard change detection triggers sync`() = testScope.runTest {
        // Given: Advanced monitoring is enabled
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // And: Mock successful sync
        coEvery { clipboardRepository.syncClipboard(any(), any(), any()) } returns Result.success(mockk {
            every { content } returns "test content"
            every { contentType } returns "text"
        })
        
        // When: Clipboard change is detected
        val clipboardContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "test content".toByteArray(),
            mimeType = "text/plain",
            source = "test-app",
            size = 12L
        )
        
        clipboardSyncManager.onClipboardChanged(clipboardContent, System.currentTimeMillis())
        
        // Then: Content should be synced
        coVerify { clipboardSyncManager.syncLocalClipboard("test content", "text") }
    }
    
    @Test
    fun `test monitoring method switching`() = testScope.runTest {
        // Given: Advanced monitoring is enabled
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // When: Switch monitoring method
        val result = clipboardSyncManager.switchMonitoringMethod(MonitoringMethod.ACCESSIBILITY_SERVICE)
        
        // Then: Should succeed
        assertTrue(result)
        
        // And: Should call monitor manager to switch method
        coVerify { clipboardMonitorManager.switchMonitoringMethod(MonitoringMethod.ACCESSIBILITY_SERVICE) }
    }
    
    @Test
    fun `test error handling and fallback`() = testScope.runTest {
        // Given: Advanced monitoring is enabled
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // When: Monitoring error occurs
        clipboardSyncManager.onMonitoringError(com.siw.clipboardsync.monitor.model.ClipboardError.AllMonitoringMethodsFailed)
        
        // Then: Advanced monitoring should be disabled
        coVerify { clipboardMonitorManager.stopMonitoring() }
    }
    
    @Test
    fun `test migration integration`() = testScope.runTest {
        // Given: Migration is not completed
        coEvery { migrationManager.isMigrationCompleted() } returns false
        coEvery { migrationManager.performMigration() } returns MonitoringMigrationManager.MigrationResult.Success
        
        // When: Initialize ClipboardSyncManager
        clipboardSyncManager.initialize()
        
        // Then: Migration should be performed
        coVerify { migrationManager.performMigration() }
        
        // And: Advanced monitoring should still be initialized
        coVerify { clipboardMonitorManager.initialize() }
    }
    
    @Test
    fun `test content filtering and deduplication`() = testScope.runTest {
        // Given: Advanced monitoring is enabled
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // When: Unsupported content type is detected
        val imageContent = ClipboardContent(
            type = ClipboardContent.ContentType.IMAGE,
            data = ByteArray(100),
            mimeType = "image/png",
            source = "test-app",
            size = 100L
        )
        
        clipboardSyncManager.onClipboardChanged(imageContent, System.currentTimeMillis())
        
        // Then: Should not attempt to sync (unsupported type)
        coVerify(exactly = 0) { clipboardSyncManager.syncLocalClipboard(any(), any()) }
    }
    
    @Test
    fun `test monitoring status reporting`() = testScope.runTest {
        // Given: Advanced monitoring is enabled
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // When: Get monitoring status
        val status = clipboardSyncManager.getAdvancedMonitoringStatus()
        
        // Then: Should return current status
        assertTrue(status.isMonitoring)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, status.currentMethod)
    }
    
    @Test
    fun `test WebSocket integration with advanced monitoring`() = testScope.runTest {
        // Given: WebSocket is connected and advanced monitoring is enabled
        every { webSocketClient.isConnected() } returns true
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // When: Clipboard change is detected
        val clipboardContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "websocket test".toByteArray(),
            mimeType = "text/plain",
            source = "test-app",
            size = 14L
        )
        
        clipboardSyncManager.onClipboardChanged(clipboardContent, System.currentTimeMillis())
        
        // Then: Should use WebSocket for sync
        coVerify { webSocketClient.sendClipboardSync(any()) }
    }
    
    @Test
    fun `test HTTP fallback when WebSocket unavailable`() = testScope.runTest {
        // Given: WebSocket is disconnected and advanced monitoring is enabled
        every { webSocketClient.isConnected() } returns false
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // And: Mock successful HTTP sync
        coEvery { clipboardRepository.syncClipboard(any(), any(), any()) } returns Result.success(mockk {
            every { content } returns "http test"
            every { contentType } returns "text"
        })
        
        // When: Clipboard change is detected
        val clipboardContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "http test".toByteArray(),
            mimeType = "text/plain",
            source = "test-app",
            size = 9L
        )
        
        clipboardSyncManager.onClipboardChanged(clipboardContent, System.currentTimeMillis())
        
        // Then: Should use HTTP for sync
        coVerify { clipboardRepository.syncClipboard("http test", "text", "test-device-id") }
    }
    
    @Test
    fun `test cleanup and resource management`() = testScope.runTest {
        // Given: Advanced monitoring is enabled
        clipboardSyncManager.initialize()
        clipboardSyncManager.enableAdvancedMonitoring()
        
        // When: Cleanup is called
        clipboardSyncManager.cleanup()
        
        // Then: Should stop advanced monitoring
        coVerify { clipboardMonitorManager.stopMonitoring() }
        
        // And: Should cleanup WebSocket
        verify { webSocketClient.cleanup() }
    }
}