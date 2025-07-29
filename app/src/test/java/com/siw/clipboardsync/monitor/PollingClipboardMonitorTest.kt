package com.siw.clipboardsync.monitor

import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.siw.clipboardsync.monitor.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class PollingClipboardMonitorTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var powerManager: PowerManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var timingOptimizer: TimingOptimizer
    private lateinit var pollingMonitor: PollingClipboardMonitor
    private lateinit var testListener: ClipboardListener

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        clipboardManager = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        batteryManager = mockk(relaxed = true)
        timingOptimizer = mockk(relaxed = true)
        testListener = mockk(relaxed = true)

        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        
        every { timingOptimizer.getOptimalReadDelay() } returns 50L
        every { timingOptimizer.shouldDebounce(any()) } returns false
        every { timingOptimizer.adjustForPowerMode(any()) } returns 1000L

        pollingMonitor = PollingClipboardMonitor(context, timingOptimizer)
    }

    @After
    fun tearDown() {
        runBlocking {
            pollingMonitor.stopMonitoring()
        }
        clearAllMocks()
    }

    @Test
    fun `startMonitoring should initialize monitoring state`() = runTest {
        // When
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()

        // Then
        assertTrue(pollingMonitor.isMonitoring())
        assertEquals(MonitoringMethod.POLLING_FALLBACK, pollingMonitor.getMonitoringMethod())
    }

    @Test
    fun `stopMonitoring should stop monitoring and clean up`() = runTest {
        // Given
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        assertTrue(pollingMonitor.isMonitoring())

        // When
        pollingMonitor.stopMonitoring()

        // Then
        assertFalse(pollingMonitor.isMonitoring())
    }

    @Test
    fun `should detect clipboard changes and notify listener`() = runTest {
        // Given
        val clipData = mockk<android.content.ClipData>(relaxed = true)
        val clipItem = mockk<android.content.ClipData.Item>(relaxed = true)
        
        every { clipboardManager.hasPrimaryClip() } returns true
        every { clipboardManager.primaryClip } returns clipData
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0) } returns clipItem
        every { clipItem.text } returns "test content"

        coEvery { testListener.onClipboardChanged(any(), any()) } just Runs

        // When
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        
        // Simulate clipboard change by changing the content
        every { clipItem.text } returns "new content"
        
        // Allow some polling cycles
        delay(1500)
        pollingMonitor.stopMonitoring()

        // Then
        coVerify(atLeast = 1) { testListener.onClipboardChanged(any(), any()) }
    }

    @Test
    fun `should adapt polling interval based on activity`() = runTest {
        // Given
        every { clipboardManager.hasPrimaryClip() } returns false
        every { powerManager.isPowerSaveMode } returns false
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()

        // When - simulate no activity for extended period
        val initialStats = pollingMonitor.getPollingStats()
        
        // Simulate user activity
        pollingMonitor.notifyUserActivity()
        delay(100)
        val activeStats = pollingMonitor.getPollingStats()

        pollingMonitor.stopMonitoring()

        // Then - interval should be different based on activity
        assertNotEquals(initialStats.currentInterval, activeStats.currentInterval)
    }

    @Test
    fun `should handle clipboard access errors gracefully`() = runTest {
        // Given
        every { clipboardManager.hasPrimaryClip() } throws SecurityException("Access denied")
        coEvery { testListener.onMonitoringError(any()) } just Runs

        // When
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        delay(1500) // Allow polling cycles
        pollingMonitor.stopMonitoring()

        // Then - should handle error without crashing
        coVerify(atLeast = 1) { testListener.onMonitoringError(any()) }
    }

    @Test
    fun `should optimize for battery when level is low`() = runTest {
        // Given - low battery scenario
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 20
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { clipboardManager.hasPrimaryClip() } returns false

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        delay(100)

        // When
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - should have battery optimization active
        assertTrue(stats.batteryOptimizationActive)
        assertTrue(stats.currentInterval > 1000L) // Should have increased interval
    }

    @Test
    fun `should maintain CPU usage under target threshold`() = runTest {
        // Given
        every { clipboardManager.hasPrimaryClip() } returns false
        every { powerManager.isPowerSaveMode } returns false
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80

        // When
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        delay(2000) // Run for 2 seconds to collect CPU stats
        
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - CPU usage should be under 1%
        assertTrue("CPU usage ${stats.averageCpuUsage}% exceeds 1% target", 
                  stats.averageCpuUsage <= 1.0)
    }

    @Test
    fun `should increase interval with consecutive no-changes`() = runTest {
        // Given
        every { clipboardManager.hasPrimaryClip() } returns false
        every { powerManager.isPowerSaveMode } returns false
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()

        // When - let it run to accumulate no-change cycles
        delay(3000)
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - should have accumulated no-changes and adapted interval
        assertTrue(stats.consecutiveNoChanges > 0)
        assertTrue(stats.currentInterval >= 1000L) // Should have base interval or higher
    }

    @Test
    fun `should handle power save mode correctly`() = runTest {
        // Given
        every { clipboardManager.hasPrimaryClip() } returns false
        every { powerManager.isPowerSaveMode } returns true
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 50

        // When
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        delay(100)
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - should adapt for power save mode
        assertTrue(stats.batteryOptimizationActive)
        assertTrue(stats.currentInterval > 1000L) // Should increase interval in power save mode
    }

    @Test
    fun `getPollingStats should return accurate performance metrics`() = runTest {
        // Given
        every { clipboardManager.hasPrimaryClip() } returns false
        every { powerManager.isPowerSaveMode } returns false
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 70

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        pollingMonitor.notifyUserActivity()
        delay(1000)

        // When
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then
        assertNotNull(stats)
        assertTrue(stats.currentInterval > 0)
        assertTrue(stats.averageCpuUsage >= 0.0)
        assertTrue(stats.consecutiveNoChanges >= 0)
        assertTrue(stats.timeSinceLastActivity >= 0)
        
        // Performance rating should be reasonable
        val rating = stats.getPerformanceRating()
        assertTrue(rating in 1..5)
    }
}