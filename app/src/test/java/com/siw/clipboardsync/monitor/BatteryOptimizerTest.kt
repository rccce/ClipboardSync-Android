package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class BatteryOptimizerTest {

    private lateinit var context: Context
    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager
    private lateinit var batteryOptimizer: BatteryOptimizer

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        batteryManager = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)

        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager

        batteryOptimizer = BatteryOptimizer(context)
    }

    @Test
    fun `should not optimize when battery is high and not in power save mode`() {
        // Given - high battery, not charging, not in power save mode
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { powerManager.isPowerSaveMode } returns false

        val baseInterval = 1000L

        // When
        val adjustedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val isOptimizationActive = batteryOptimizer.isOptimizationActive()

        // Then
        assertEquals("Should not adjust interval for high battery", baseInterval, adjustedInterval)
        assertFalse("Optimization should not be active", isOptimizationActive)
    }

    @Test
    fun `should optimize aggressively when battery is critical`() {
        // Given - critical battery level
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 10
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { powerManager.isPowerSaveMode } returns false

        val baseInterval = 1000L

        // When
        val adjustedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val isOptimizationActive = batteryOptimizer.isOptimizationActive()

        // Then
        assertTrue("Should increase interval significantly for critical battery", 
                  adjustedInterval >= baseInterval * 3)
        assertTrue("Optimization should be active for critical battery", isOptimizationActive)
    }

    @Test
    fun `should optimize moderately when battery is low`() {
        // Given - low battery level
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 25
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { powerManager.isPowerSaveMode } returns false

        val baseInterval = 1000L

        // When
        val adjustedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val isOptimizationActive = batteryOptimizer.isOptimizationActive()

        // Then
        assertTrue("Should increase interval for low battery", 
                  adjustedInterval > baseInterval)
        assertTrue("Should increase interval moderately", 
                  adjustedInterval >= (baseInterval * 2.0).toLong())
        assertTrue("Optimization should be active for low battery", isOptimizationActive)
    }

    @Test
    fun `should reduce optimization when charging`() {
        // Given - low battery but charging
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 20
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_CHARGING
        every { powerManager.isPowerSaveMode } returns false

        val baseInterval = 1000L

        // When
        val chargingInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        
        // Compare with not charging
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        val notChargingInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)

        // Then
        assertTrue("Charging should reduce optimization impact", 
                  chargingInterval < notChargingInterval)
        assertTrue("Should still have some optimization when charging at low battery", 
                  chargingInterval > baseInterval)
    }

    @Test
    fun `should optimize when in power save mode regardless of battery level`() {
        // Given - medium battery but power save mode
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 60
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { powerManager.isPowerSaveMode } returns true

        val baseInterval = 1000L

        // When
        val adjustedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val isOptimizationActive = batteryOptimizer.isOptimizationActive()

        // Then
        assertTrue("Should optimize in power save mode", adjustedInterval > baseInterval)
        assertTrue("Optimization should be active in power save mode", isOptimizationActive)
    }

    @Test
    fun `should handle battery manager errors gracefully`() {
        // Given - battery manager throws exceptions
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } throws RuntimeException("Battery error")
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } throws RuntimeException("Status error")
        every { powerManager.isPowerSaveMode } returns false

        val baseInterval = 1000L

        // When
        val adjustedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val isOptimizationActive = batteryOptimizer.isOptimizationActive()

        // Then - should handle gracefully and assume good conditions
        assertEquals("Should not adjust interval when unable to read battery", 
                    baseInterval, adjustedInterval)
        assertFalse("Should not optimize when unable to read battery state", isOptimizationActive)
    }

    @Test
    fun `should provide accurate battery optimization info`() {
        // Given
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 35
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_CHARGING
        every { powerManager.isPowerSaveMode } returns true

        // When
        val info = batteryOptimizer.getBatteryOptimizationInfo()

        // Then
        assertEquals(35, info.batteryLevel)
        assertTrue(info.isCharging)
        assertTrue(info.isPowerSaveMode)
        assertTrue("Should recommend optimization", info.recommendedMultiplier > 1.0)
        assertTrue("Should be optimizing", info.optimizationActive)
    }

    @Test
    fun `should calculate appropriate multipliers for different battery levels`() {
        // Test critical level
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 10
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { powerManager.isPowerSaveMode } returns false
        
        val criticalInfo = batteryOptimizer.getBatteryOptimizationInfo()
        
        // Test low level
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 25
        val lowInfo = batteryOptimizer.getBatteryOptimizationInfo()
        
        // Test medium level
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 45
        val mediumInfo = batteryOptimizer.getBatteryOptimizationInfo()
        
        // Test high level
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 80
        val highInfo = batteryOptimizer.getBatteryOptimizationInfo()

        // Then - multipliers should decrease as battery level increases
        assertTrue("Critical should have highest multiplier", 
                  criticalInfo.recommendedMultiplier > lowInfo.recommendedMultiplier)
        assertTrue("Low should have higher multiplier than medium", 
                  lowInfo.recommendedMultiplier > mediumInfo.recommendedMultiplier)
        assertTrue("Medium should have higher multiplier than high", 
                  mediumInfo.recommendedMultiplier > highInfo.recommendedMultiplier)
        assertEquals("High battery should have no multiplier", 1.0, highInfo.recommendedMultiplier, 0.1)
    }

    @Test
    fun `should handle full battery status correctly`() {
        // Given - battery full
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 100
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) } returns BatteryManager.BATTERY_STATUS_FULL
        every { powerManager.isPowerSaveMode } returns false

        val baseInterval = 1000L

        // When
        val adjustedInterval = batteryOptimizer.adjustIntervalForBattery(baseInterval)
        val info = batteryOptimizer.getBatteryOptimizationInfo()

        // Then
        assertEquals("Should not adjust for full battery", baseInterval, adjustedInterval)
        assertTrue("Full battery should be considered charging", info.isCharging)
        assertFalse("Should not optimize when battery is full", info.optimizationActive)
    }
}