package com.siw.clipboardsync.monitor

import android.content.Context
import com.siw.clipboardsync.monitor.error.ClipboardErrorHandler
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.MonitoringStrategy
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for ClipboardMonitorManager.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardMonitorManagerTest {
    
    private lateinit var context: Context
    private lateinit var strategyFactory: MonitoringStrategyFactory
    private lateinit var errorHandler: ClipboardErrorHandler
    private lateinit var systemLevelMonitor: SystemLevelClipboardMonitor
    private lateinit var accessibilityMonitor: AccessibilityClipboardMonitor
    private lateinit var foregroundServiceMonitor: ForegroundServiceClipboardMonitor
    private lateinit var pollingMonitor: PollingClipboardMonitor
    private lateinit var monitorManager: ClipboardMonitorManager
    private lateinit var testListener: ClipboardListener
    
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        strategyFactory = mockk()
        errorHandler = mockk(relaxed = true)
        systemLevelMonitor = mockk(relaxed = true)
        accessibilityMonitor = mockk(relaxed = true)
        foregroundServiceMonitor = mockk(relaxed = true)
        pollingMonitor = mockk(relaxed = true)
        testListener = mockk(relaxed = true)
        
        monitorManager = ClipboardMonitorManager(
            context = context,
            strategyFactory = strategyFactory,
            errorHandler = errorHandler,
            systemLevelMonitor = systemLevelMonitor,
            accessibilityMonitor = accessibilityMonitor,
            foregroundServiceMonitor = foregroundServiceMonitor,
            pollingMonitor = pollingMonitor
        )
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `initialize creates fallback chain successfully`() = runTest {
        // Given: Strategy factory returns available strategies
        val strategies = listOf(
            createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true),
            createMockStrategy(MonitoringMethod.ACCESSIBILITY_SERVICE, 70, true),
            createMockStrategy(MonitoringMethod.POLLING_FALLBACK, 10, true)
        )
        coEvery { strategyFactory.createFallbackChain() } returns strategies
        
        // When: Initializing manager
        monitorManager.initialize()
        
        // Then: Manager should be initialized
        coVerify { strategyFactory.createFallbackChain() }
    }
    
    @Test
    fun `startMonitoring with optimal strategy succeeds`() = runTest {
        // Given: Optimal strategy available
        val optimalStrategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val fallbackChain = listOf(optimalStrategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns optimalStrategy
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Then: Optimal strategy should be used
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, monitorManager.currentMethod.value)
        coVerify { systemLevelMonitor.startMonitoring() }
        verify { systemLevelMonitor.setClipboardListener(monitorManager) }
    }
    
    @Test
    fun `startMonitoring falls back when optimal strategy fails`() = runTest {
        // Given: Optimal strategy fails, fallback available
        val failingStrategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val fallbackStrategy = createMockStrategy(MonitoringMethod.ACCESSIBILITY_SERVICE, 70, true)
        val fallbackChain = listOf(failingStrategy, fallbackStrategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns failingStrategy
        coEvery { systemLevelMonitor.startMonitoring() } throws RuntimeException("System hooks failed")
        coEvery { accessibilityMonitor.startMonitoring() } just Runs
        every { accessibilityMonitor.getMonitoringMethod() } returns MonitoringMethod.ACCESSIBILITY_SERVICE
        
        // When: Starting monitoring
        monitorManager.startMonitoring(testListener)
        
        // Give time for fallback chain to execute
        delay(100)
        
        // Then: Should fall back to accessibility service
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitorManager.currentMethod.value)
        coVerify { systemLevelMonitor.startMonitoring() }
        coVerify { accessibilityMonitor.startMonitoring() }
    }
    
    @Test
    fun `stopMonitoring stops current monitor and clears state`() = runTest {
        // Given: Manager is monitoring with system hooks
        val strategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val fallbackChain = listOf(strategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns strategy
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        coEvery { systemLevelMonitor.stopMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        
        monitorManager.startMonitoring(testListener)
        assertTrue(monitorManager.isMonitoring.value)
        
        // When: Stopping monitoring
        monitorManager.stopMonitoring()
        
        // Then: Monitor should be stopped and state cleared
        assertFalse(monitorManager.isMonitoring.value)
        assertNull(monitorManager.currentMethod.value)
        assertNull(monitorManager.currentStrategy.value)
        coVerify { systemLevelMonitor.stopMonitoring() }
    }
    
    @Test
    fun `switchMonitoringMethod changes to target method successfully`() = runTest {
        // Given: Manager is monitoring with system hooks
        val systemStrategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val accessibilityStrategy = createMockStrategy(MonitoringMethod.ACCESSIBILITY_SERVICE, 70, true)
        val fallbackChain = listOf(systemStrategy, accessibilityStrategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns systemStrategy
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        coEvery { systemLevelMonitor.stopMonitoring() } just Runs
        coEvery { accessibilityMonitor.startMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        every { accessibilityMonitor.getMonitoringMethod() } returns MonitoringMethod.ACCESSIBILITY_SERVICE
        
        monitorManager.startMonitoring(testListener)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, monitorManager.currentMethod.value)
        
        // When: Switching to accessibility service
        monitorManager.switchMonitoringMethod(MonitoringMethod.ACCESSIBILITY_SERVICE)
        
        // Then: Should switch to accessibility service
        assertTrue(monitorManager.isMonitoring.value)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, monitorManager.currentMethod.value)
        coVerify { systemLevelMonitor.stopMonitoring() }
        coVerify { accessibilityMonitor.startMonitoring() }
    }
    
    @Test
    fun `switchMonitoringMethod fails when target method unavailable`() = runTest {
        // Given: Manager is monitoring, but target method is unavailable
        val systemStrategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val unavailableStrategy = createMockStrategy(MonitoringMethod.XPOSED_HOOKS, 90, false)
        val fallbackChain = listOf(systemStrategy, unavailableStrategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns systemStrategy
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        
        monitorManager.startMonitoring(testListener)
        
        // When: Trying to switch to unavailable method
        try {
            monitorManager.switchMonitoringMethod(MonitoringMethod.XPOSED_HOOKS)
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            // Then: Should throw exception and maintain current monitoring
            assertTrue(e.clipboardError is ClipboardError.MonitoringMethodUnavailable)
            assertTrue(monitorManager.isMonitoring.value)
            assertEquals(MonitoringMethod.SYSTEM_HOOKS, monitorManager.currentMethod.value)
        }
    }
    
    @Test
    fun `onClipboardChanged forwards events to external listener`() = runTest {
        // Given: Manager with external listener
        val content = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = "test".toByteArray(),
            mimeType = "text/plain",
            timestamp = System.currentTimeMillis(),
            source = "test",
            size = 4L
        )
        val timestamp = System.currentTimeMillis()
        
        coEvery { testListener.onClipboardChanged(any(), any()) } just Runs
        
        // When: Clipboard change occurs
        monitorManager.onClipboardChanged(content, timestamp)
        
        // Then: External listener should receive event
        coVerify { testListener.onClipboardChanged(content, timestamp) }
    }
    
    @Test
    fun `onMonitoringError handles error and forwards to listener`() = runTest {
        // Given: Manager with external listener and error handler
        val error = ClipboardError.SystemHookFailed("test hook", RuntimeException("test"))
        val strategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val fallbackChain = listOf(strategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns strategy
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        coEvery { testListener.onMonitoringError(any()) } just Runs
        coEvery { errorHandler.handleError(any(), any()) } just Runs
        
        monitorManager.startMonitoring(testListener)
        
        // When: Monitoring error occurs
        monitorManager.onMonitoringError(error)
        
        // Then: Error should be handled and forwarded
        coVerify { errorHandler.handleError(error, MonitoringMethod.SYSTEM_HOOKS) }
        coVerify { testListener.onMonitoringError(error) }
    }
    
    @Test
    fun `getMonitoringStatus returns current state`() = runTest {
        // Given: Manager is monitoring
        val strategy = createMockStrategy(MonitoringMethod.SYSTEM_HOOKS, 100, true)
        val fallbackChain = listOf(strategy)
        
        coEvery { strategyFactory.createFallbackChain() } returns fallbackChain
        coEvery { strategyFactory.selectOptimalStrategy() } returns strategy
        coEvery { systemLevelMonitor.startMonitoring() } just Runs
        every { systemLevelMonitor.getMonitoringMethod() } returns MonitoringMethod.SYSTEM_HOOKS
        
        monitorManager.startMonitoring(testListener)
        
        // When: Getting monitoring status
        val status = monitorManager.getMonitoringStatus()
        
        // Then: Status should reflect current state
        assertTrue(status.isMonitoring)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, status.currentMethod)
        assertEquals(strategy, status.currentStrategy)
        assertEquals(fallbackChain, status.availableStrategies)
        assertEquals(0, status.fallbackIndex)
    }
    
    @Test
    fun `startMonitoring throws exception when no strategies available`() = runTest {
        // Given: No strategies available
        coEvery { strategyFactory.createFallbackChain() } returns emptyList()
        coEvery { strategyFactory.selectOptimalStrategy() } returns null
        
        // When: Trying to start monitoring
        try {
            monitorManager.startMonitoring(testListener)
            fail("Expected ClipboardMonitorException")
        } catch (e: ClipboardMonitorException) {
            // Then: Should throw appropriate exception
            assertTrue(e.clipboardError is ClipboardError.NoMonitoringMethodAvailable)
            assertFalse(monitorManager.isMonitoring.value)
        }
    }
    
    private fun createMockStrategy(
        method: MonitoringMethod,
        priority: Int,
        isAvailable: Boolean
    ): MonitoringStrategy {
        return MonitoringStrategy(
            method = method,
            priority = priority,
            isAvailable = isAvailable,
            capabilities = setOf(MonitoringStrategy.Capability.BACKGROUND_ACCESS)
        )
    }
}