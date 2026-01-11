package com.siw.clipboardsync.compatibility

import android.content.Context
import android.os.Build
import com.siw.clipboardsync.monitor.*
import com.siw.clipboardsync.monitor.model.*
import com.siw.clipboardsync.service.RootDetectionService
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.junit.Assert.*

/**
 * Compatibility tests across Android versions and OEM customizations.
 * Tests monitoring functionality on different Android API levels and manufacturer modifications.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidCompatibilityTestSuite {
    
    private lateinit var context: Context
    private lateinit var rootDetectionService: RootDetectionService
    private lateinit var timingOptimizer: TimingOptimizer
    
    private val testScope = TestScope()
    
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        rootDetectionService = RootDetectionService(context)
        timingOptimizer = AdaptiveTimingOptimizer(context)
    }
    
    @After
    fun tearDown() {
        testScope.cancel()
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.N]) // Android 7.0 (API 24) - Min SDK
    fun `test monitoring on Android 7_0 minimum SDK`() = testScope.runTest {
        // Given: Android 7.0 environment
        val strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        
        // When: Get available strategies
        val strategies = strategyFactory.getAvailableStrategies()
        
        // Then: Should have basic strategies available
        assertTrue(strategies.isNotEmpty(), "No strategies available on Android 7.0")
        
        // Should have polling fallback at minimum
        assertTrue(
            strategies.any { it.method == MonitoringMethod.POLLING_FALLBACK },
            "Polling fallback should be available on all Android versions"
        )
        
        // Accessibility service should be available
        assertTrue(
            strategies.any { it.method == MonitoringMethod.ACCESSIBILITY_SERVICE },
            "Accessibility service should be available on Android 7.0+"
        )
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.O]) // Android 8.0 (API 26)
    fun `test monitoring on Android 8_0 with background restrictions`() = testScope.runTest {
        // Given: Android 8.0 with background service limitations
        val monitor = ForegroundServiceClipboardMonitor(context, timingOptimizer)
        
        // When: Start monitoring
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Then: Should adapt to background restrictions
        assertTrue(monitor.isMonitoring(), "Foreground service should work on Android 8.0+")
        assertEquals(MonitoringMethod.FOREGROUND_SERVICE, monitor.getMonitoringMethod())
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.P]) // Android 9.0 (API 28)
    fun `test monitoring on Android 9_0 with privacy changes`() = testScope.runTest {
        // Given: Android 9.0 with enhanced privacy
        val strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        val strategies = strategyFactory.getAvailableStrategies()
        
        // When: Check available strategies
        val accessibilityStrategy = strategies.find { it.method == MonitoringMethod.ACCESSIBILITY_SERVICE }
        
        // Then: Accessibility service should still be available
        assertNotNull(accessibilityStrategy, "Accessibility service should work on Android 9.0")
        assertTrue(accessibilityStrategy.isAvailable, "Accessibility service should be available")
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.Q]) // Android 10.0 (API 29) - Major clipboard restrictions
    fun `test monitoring on Android 10_0 with clipboard restrictions`() = testScope.runTest {
        // Given: Android 10.0 with background clipboard access restrictions
        val strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        val strategies = strategyFactory.getAvailableStrategies()
        
        // When: Check strategy priorities
        val sortedStrategies = strategies.sortedBy { it.priority }
        
        // Then: Should prioritize non-background methods
        val topStrategy = sortedStrategies.first()
        assertTrue(
            topStrategy.method in listOf(
                MonitoringMethod.SYSTEM_HOOKS,
                MonitoringMethod.ACCESSIBILITY_SERVICE,
                MonitoringMethod.FOREGROUND_SERVICE
            ),
            "Top strategy should handle Android 10+ restrictions: ${topStrategy.method}"
        )
        
        // Polling should have lower priority due to restrictions
        val pollingStrategy = strategies.find { it.method == MonitoringMethod.POLLING_FALLBACK }
        assertNotNull(pollingStrategy, "Polling should still be available as fallback")
        assertTrue(pollingStrategy.priority > topStrategy.priority, "Polling should have lower priority on Android 10+")
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // Android 11.0 (API 30)
    fun `test monitoring on Android 11_0 with scoped storage`() = testScope.runTest {
        // Given: Android 11.0 with scoped storage
        val fileProcessor = FileProcessor(context)
        
        // When: Process file content
        val fileContent = ClipboardContent(
            type = ClipboardContent.ContentType.FILE,
            data = "content://com.android.providers.media.documents/document/image%3A123".toByteArray(),
            mimeType = "image/jpeg",
            source = "test",
            size = 50L
        )
        
        // Then: Should handle scoped storage URIs
        val result = fileProcessor.process(fileContent)
        assertNotNull(result, "File processor should handle scoped storage on Android 11+")
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // Android 12.0 (API 31)
    fun `test monitoring on Android 12_0 with enhanced privacy`() = testScope.runTest {
        // Given: Android 12.0 with privacy dashboard
        val monitor = AccessibilityClipboardMonitor(context, timingOptimizer)
        
        // When: Start monitoring
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Then: Should work with privacy enhancements
        assertTrue(monitor.isMonitoring(), "Accessibility monitor should work on Android 12+")
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // Android 13.0 (API 33)
    fun `test monitoring on Android 13_0 with notification permissions`() = testScope.runTest {
        // Given: Android 13.0 with runtime notification permissions
        val monitor = ForegroundServiceClipboardMonitor(context, timingOptimizer)
        
        // When: Start monitoring (would require notification permission)
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Then: Should handle notification permission requirements
        assertTrue(monitor.isMonitoring(), "Foreground service should handle notification permissions on Android 13+")
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE]) // Android 14.0 (API 34)
    fun `test monitoring on Android 14_0 latest features`() = testScope.runTest {
        // Given: Android 14.0 with latest privacy and security features
        val strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        val strategies = strategyFactory.getAvailableStrategies()
        
        // When: Check strategy availability
        val availableStrategies = strategies.filter { it.isAvailable }
        
        // Then: Should have working strategies on latest Android
        assertTrue(availableStrategies.isNotEmpty(), "Should have available strategies on Android 14")
        
        // Should prioritize system-level or accessibility methods
        val topStrategy = availableStrategies.minByOrNull { it.priority }
        assertNotNull(topStrategy, "Should have a top priority strategy")
        assertTrue(
            topStrategy.method in listOf(
                MonitoringMethod.SYSTEM_HOOKS,
                MonitoringMethod.ACCESSIBILITY_SERVICE
            ),
            "Top strategy should be system-level or accessibility on Android 14"
        )
    }
    
    // OEM Customization Tests
    
    @Test
    fun `test Samsung One UI compatibility`() = testScope.runTest {
        // Given: Samsung device simulation
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "samsung")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "samsung")
        
        val rootDetection = RootDetectionService(context)
        
        // When: Check root detection on Samsung
        val capabilities = rootDetection.getRootCapabilities()
        
        // Then: Should handle Samsung Knox and security features
        assertNotNull(capabilities, "Should detect capabilities on Samsung devices")
        
        // Samsung devices often have Knox which affects root detection
        if (capabilities.hasSystemHooks) {
            assertTrue(capabilities.rootMethod in listOf("magisk", "supersu", "unknown"))
        }
    }
    
    @Test
    fun `test Xiaomi MIUI compatibility`() = testScope.runTest {
        // Given: Xiaomi device simulation
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "xiaomi")
        
        val strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        
        // When: Get strategies on MIUI
        val strategies = strategyFactory.getAvailableStrategies()
        
        // Then: Should handle MIUI's aggressive background restrictions
        val foregroundStrategy = strategies.find { it.method == MonitoringMethod.FOREGROUND_SERVICE }
        assertNotNull(foregroundStrategy, "Foreground service should be prioritized on MIUI")
        
        // MIUI often requires foreground service for background operations
        assertTrue(foregroundStrategy.isAvailable, "Foreground service should be available on MIUI")
    }
    
    @Test
    fun `test Huawei EMUI compatibility`() = testScope.runTest {
        // Given: Huawei device simulation
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "HUAWEI")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "huawei")
        
        val monitor = AccessibilityClipboardMonitor(context, timingOptimizer)
        
        // When: Start monitoring on EMUI
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Then: Should work with EMUI's power management
        assertTrue(monitor.isMonitoring(), "Accessibility service should work on EMUI")
    }
    
    @Test
    fun `test OnePlus OxygenOS compatibility`() = testScope.runTest {
        // Given: OnePlus device simulation
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "OnePlus")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "oneplus")
        
        val batteryOptimizer = BatteryOptimizer(context)
        
        // When: Check battery optimization
        val isLowPower = batteryOptimizer.isLowPowerMode()
        val adjustedTiming = batteryOptimizer.adjustTimingForPowerMode(100L, isLowPower)
        
        // Then: Should handle OxygenOS battery optimization
        assertTrue(adjustedTiming >= 100L, "Should adjust timing for battery optimization")
    }
    
    @Test
    fun `test Google Pixel stock Android compatibility`() = testScope.runTest {
        // Given: Google Pixel device simulation
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "google")
        
        val strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
        val strategies = strategyFactory.getAvailableStrategies()
        
        // When: Check strategies on stock Android
        val systemStrategy = strategies.find { it.method == MonitoringMethod.SYSTEM_HOOKS }
        
        // Then: Should work well with stock Android
        if (systemStrategy != null) {
            // Stock Android typically has fewer restrictions for system-level access
            assertTrue(strategies.size >= 3, "Should have multiple strategies available on stock Android")
        }
    }
    
    @Test
    fun `test cross version clipboard content compatibility`() = testScope.runTest {
        // Test clipboard content handling across different Android versions
        val testVersions = listOf(
            Build.VERSION_CODES.N,    // 24
            Build.VERSION_CODES.O,    // 26
            Build.VERSION_CODES.P,    // 28
            Build.VERSION_CODES.Q,    // 29
            Build.VERSION_CODES.R,    // 30
            Build.VERSION_CODES.S,    // 31
            Build.VERSION_CODES.TIRAMISU // 33
        )
        
        testVersions.forEach { apiLevel ->
            // Given: Different Android version
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", apiLevel)
            
            val textProcessor = TextProcessor()
            val content = ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = "test content for API $apiLevel".toByteArray(),
                mimeType = "text/plain",
                source = "compatibility-test",
                size = 30L
            )
            
            // When: Process content
            val result = textProcessor.process(content)
            
            // Then: Should work across all supported versions
            assertNotNull(result, "Content processing should work on API $apiLevel")
            assertEquals(content.data.size, result.data.size, "Content size should be preserved on API $apiLevel")
        }
    }
    
    @Test
    fun `test accessibility service compatibility across versions`() = testScope.runTest {
        // Test accessibility service across different Android versions
        val testVersions = listOf(
            Build.VERSION_CODES.N to true,    // Should work
            Build.VERSION_CODES.O to true,    // Should work
            Build.VERSION_CODES.P to true,    // Should work
            Build.VERSION_CODES.Q to true,    // Should work (important for Android 10+)
            Build.VERSION_CODES.R to true,    // Should work
            Build.VERSION_CODES.S to true,    // Should work
            Build.VERSION_CODES.TIRAMISU to true // Should work
        )
        
        testVersions.forEach { (apiLevel, shouldWork) ->
            // Given: Different Android version
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", apiLevel)
            
            val monitor = AccessibilityClipboardMonitor(context, timingOptimizer)
            
            // When: Try to start monitoring
            monitor.setListener(mockk(relaxed = true))
            
            try {
                monitor.startMonitoring()
                val isWorking = monitor.isMonitoring()
                
                // Then: Should match expected compatibility
                if (shouldWork) {
                    assertTrue(isWorking, "Accessibility service should work on API $apiLevel")
                } else {
                    assertFalse(isWorking, "Accessibility service should not work on API $apiLevel")
                }
            } catch (e: Exception) {
                if (shouldWork) {
                    fail("Accessibility service failed unexpectedly on API $apiLevel: ${e.message}")
                }
                // Expected failure on unsupported versions
            } finally {
                monitor.stopMonitoring()
            }
        }
    }
    
    @Test
    fun `test root detection across OEM customizations`() = testScope.runTest {
        // Test root detection on different OEM customizations
        val oemConfigs = listOf(
            "samsung" to "samsung",
            "Xiaomi" to "xiaomi", 
            "HUAWEI" to "huawei",
            "OnePlus" to "oneplus",
            "Google" to "google",
            "LGE" to "lge",
            "sony" to "sony"
        )
        
        oemConfigs.forEach { (manufacturer, brand) ->
            // Given: Different OEM
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", manufacturer)
            ReflectionHelpers.setStaticField(Build::class.java, "BRAND", brand)
            
            val rootDetection = RootDetectionService(context)
            
            // When: Check root detection
            val isRooted = rootDetection.isRooted()
            val capabilities = rootDetection.getRootCapabilities()
            
            // Then: Should handle OEM-specific root detection
            assertNotNull(capabilities, "Should return capabilities for $manufacturer")
            assertTrue(capabilities.rootMethod in listOf("none", "magisk", "supersu", "kingroot", "unknown"))
            
            // Root status should be consistent with capabilities
            if (isRooted) {
                assertTrue(capabilities.rootMethod != "none", "Root method should not be 'none' if device is rooted")
            }
        }
    }
}