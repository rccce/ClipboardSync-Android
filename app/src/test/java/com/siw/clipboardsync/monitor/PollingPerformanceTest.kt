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
import kotlin.system.measureTimeMillis

/**
 * Performance tests specifically focused on validating CPU and battery impact
 * of the polling clipboard monitor.
 */
class PollingPerformanceTest {

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
        
        every { timingOptimizer.getOptimalReadDelay() } returns 10L
        every { timingOptimizer.shouldDebounce(any()) } returns false
        every { timingOptimizer.adjustForPowerMode(any()) } returns 1000L
        every { clipboardManager.hasPrimaryClip() } returns false
        every { powerManager.isPowerSaveMode } returns false

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
    fun `CPU usage should stay under 1 percent during normal operation`() = runTest {
        // Given - normal battery and power conditions
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING

        // When - run monitoring for extended period
        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        delay(5000) // Run for 5 seconds to get stable CPU measurements
        
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - CPU usage should be under 1%
        assertTrue(
            "CPU usage ${stats.averageCpuUsage}% exceeds 1% target", 
            stats.averageCpuUsage <= 1.0
        )
        
        // Performance rating should be good (4-5)
        assertTrue(
            "Performance rating ${stats.getPerformanceRating()} is too low",
            stats.getPerformanceRating() >= 4
        )
    }

    @Test
    fun `polling interval should adapt to reduce CPU load when usage is high`() = runTest {
        // Given - simulate high CPU usage scenario
        val cpuTracker = CpuUsageTracker()
        
        // Simulate high CPU cycles
        repeat(50) {
            cpuTracker.recordCycle(100L) // 100ms cycles (high CPU usage)
        }
        
        val baseInterval = 1000L
        
        // When
        val adjustedInterval = cpuTracker.adjustIntervalForCpuBudget(baseInterval)
        
        // Then - interval should be increased to reduce CPU load
        assertTrue(
            "Interval not adjusted for high CPU usage: $adjustedInterval vs $baseInterval",
            adjustedInterval >= baseInterval
        )
    }

    @Test
    fun `battery optimization should significantly increase intervals when battery is low`() = runTest {
        // Given - low battery scenario
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 15 // Critical
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING

        val batteryOptimizer = BatteryOptimizer(context)
        val baseInterval = 1000L

        // When
        val optimizedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val optimizationInfo = batteryOptimizer.getBatteryOptimizationInfo()

        // Then - should significantly increase interval for battery saving
        assertTrue(
            "Battery optimization not aggressive enough: $optimizedInterval vs $baseInterval",
            optimizedInterval >= baseInterval * 3 // Should be at least 3x longer
        )
        assertTrue("Battery optimization should be active", optimizationInfo.optimizationActive)
        assertEquals(15, optimizationInfo.batteryLevel)
    }

    @Test
    fun `charging should reduce battery optimization impact`() = runTest {
        // Given - low battery but charging
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 20
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_CHARGING

        val batteryOptimizer = BatteryOptimizer(context)
        val baseInterval = 1000L

        // When
        val chargingInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        
        // Compare with non-charging scenario
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        val notChargingInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)

        // Then - charging should result in less aggressive optimization
        assertTrue(
            "Charging should reduce optimization: charging=$chargingInterval, not_charging=$notChargingInterval",
            chargingInterval < notChargingInterval
        )
    }

    @Test
    fun `power save mode should double the polling interval`() = runTest {
        // Given
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 50
        every { powerManager.isPowerSaveMode } returns true

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        delay(100)

        // When
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - power save mode should increase interval
        assertTrue(
            "Power save mode should increase interval significantly: ${stats.currentInterval}ms",
            stats.currentInterval >= 2000L // Should be at least 2x base interval
        )
        assertTrue("Battery optimization should be active in power save mode", 
                  stats.batteryOptimizationActive)
    }

    @Test
    fun `consecutive no-changes should progressively increase polling interval`() = runTest {
        // Given - scenario with no clipboard changes
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80
        every { clipboardManager.hasPrimaryClip() } returns false

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()

        // When - let it accumulate no-change cycles
        delay(10000) // 10 seconds should accumulate many no-change cycles
        val stats = pollingMonitor.getPollingStats()
        pollingMonitor.stopMonitoring()

        // Then - should have many no-changes and adapted interval
        assertTrue(
            "Should have accumulated no-change cycles: ${stats.consecutiveNoChanges}",
            stats.consecutiveNoChanges > 5
        )
        
        // Interval should be increased due to inactivity
        assertTrue(
            "Interval should increase with no activity: ${stats.currentInterval}ms",
            stats.currentInterval > 1000L
        )
    }

    @Test
    fun `user activity should reset adaptive intervals`() = runTest {
        // Given
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80
        every { clipboardManager.hasPrimaryClip() } returns false

        pollingMonitor.setClipboardListener(testListener)
        pollingMonitor.startMonitoring()
        
        // Let it build up inactivity
        delay(2000)
        val inactiveStats = pollingMonitor.getPollingStats()
        
        // When - notify user activity
        pollingMonitor.notifyUserActivity()
        delay(100)
        val activeStats = pollingMonitor.getPollingStats()
        
        pollingMonitor.stopMonitoring()

        // Then - activity should reset timing considerations
        assertTrue(
            "User activity should reduce time since last activity",
            activeStats.timeSinceLastActivity < inactiveStats.timeSinceLastActivity
        )
    }

    @Test
    fun `stress test with rapid clipboard changes should maintain performance`() = runTest {
        // Given - simulate rapid clipboard changes
        val clipData = mockk<android.content.ClipData>(relaxed = true)
        val clipItem = mockk<android.content.ClipData.Item>(relaxed = true)
        
        every { clipboardManager.hasPrimaryClip() } returns true
        every { clipboardManager.primaryClip } returns clipData
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0) } returns clipItem
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80

        var contentCounter = 0
        every { clipItem.text } answers { "content_${contentCounter++}" }

        coEvery { testListener.onClipboardChanged(any(), any()) } just Runs

        // When - run with rapid changes for extended period
        val executionTime = measureTimeMillis {
            pollingMonitor.setClipboardListener(testListener)
            pollingMonitor.startMonitoring()
            delay(5000) // 5 seconds of rapid changes
            pollingMonitor.stopMonitoring()
        }

        val stats = pollingMonitor.getPollingStats()

        // Then - should handle rapid changes without performance degradation
        assertTrue(
            "Execution took too long: ${executionTime}ms",
            executionTime < 6000 // Should complete within reasonable time
        )
        
        assertTrue(
            "CPU usage too high under stress: ${stats.averageCpuUsage}%",
            stats.averageCpuUsage <= 2.0 // Allow slightly higher under stress but still reasonable
        )
        
        // Should have detected multiple changes
        coVerify(atLeast = 3) { testListener.onClipboardChanged(any(), any()) }
    }

    @Test
    fun `CPU tracker should accurately measure and limit usage`() {
        // Given
        val cpuTracker = CpuUsageTracker()
        
        // Simulate various cycle times
        val cycleTimes = listOf(5L, 10L, 15L, 8L, 12L, 20L, 6L, 9L, 11L, 14L)
        cycleTimes.forEach { cpuTracker.recordCycle(it) }
        
        // When
        val avgUsage = cpuTracker.getAverageCpuUsage()
        val stats = cpuTracker.getCpuStats()
        val adjustedInterval = cpuTracker.adjustIntervalForCpuBudget(1000L)
        
        // Then
        assertTrue("Average usage should be calculated", avgUsage >= 0.0)
        assertEquals("Should track all cycles", cycleTimes.size.toLong(), stats.totalCycles)
        assertEquals("Should have correct sample count", cycleTimes.size, stats.recentSamples)
        assertTrue("Adjusted interval should be reasonable", adjustedInterval >= 1000L)
        
        // Stats should be consistent
        assertEquals(cycleTimes.average(), stats.averageCycleTimeMs, 0.1)
        assertEquals(cycleTimes.maxOrNull()?.toDouble(), stats.maxCycleTimeMs)
        assertEquals(cycleTimes.minOrNull()?.toDouble(), stats.minCycleTimeMs)
    }
}