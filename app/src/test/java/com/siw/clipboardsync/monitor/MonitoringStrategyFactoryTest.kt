package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.Build
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.MonitoringStrategy
import com.siw.clipboardsync.monitor.model.RootCapabilities
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MonitoringStrategyFactory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R]) // Android 11
class MonitoringStrategyFactoryTest {
    
    private lateinit var context: Context
    private lateinit var rootDetectionService: RootDetectionService
    private lateinit var strategyFactory: MonitoringStrategyFactory
    
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        rootDetectionService = mockk()
        
        // Mock AccessibilityPermissionManager constructor
        mockkConstructor(AccessibilityPermissionManager::class)
        
        strategyFactory = MonitoringStrategyFactory(context, rootDetectionService)
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `createAvailableStrategies returns all strategies when all capabilities available`() = runTest {
        // Given: Device with all capabilities
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = true,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns true
        
        // When: Creating available strategies
        val strategies = strategyFactory.createAvailableStrategies()
        
        // Then: All strategies should be available
        assertEquals(5, strategies.size)
        
        val methodsFound = strategies.map { it.method }.toSet()
        assertTrue(methodsFound.contains(MonitoringMethod.SYSTEM_HOOKS))
        assertTrue(methodsFound.contains(MonitoringMethod.XPOSED_HOOKS))
        assertTrue(methodsFound.contains(MonitoringMethod.ACCESSIBILITY_SERVICE))
        assertTrue(methodsFound.contains(MonitoringMethod.FOREGROUND_SERVICE))
        assertTrue(methodsFound.contains(MonitoringMethod.POLLING_FALLBACK))
        
        // Verify priority order (highest first)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, strategies[0].method)
        assertEquals(MonitoringMethod.XPOSED_HOOKS, strategies[1].method)
    }
    
    @Test
    fun `createAvailableStrategies returns limited strategies for non-root device`() = runTest {
        // Given: Non-root device
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE,
            suBinaryPath = null,
            isRootAccessible = false
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns false
        
        // When: Creating available strategies
        val strategies = strategyFactory.createAvailableStrategies()
        
        // Then: Only non-root strategies should be available
        assertEquals(3, strategies.size)
        
        val methodsFound = strategies.map { it.method }.toSet()
        assertFalse(methodsFound.contains(MonitoringMethod.SYSTEM_HOOKS))
        assertFalse(methodsFound.contains(MonitoringMethod.XPOSED_HOOKS))
        assertTrue(methodsFound.contains(MonitoringMethod.ACCESSIBILITY_SERVICE))
        assertTrue(methodsFound.contains(MonitoringMethod.FOREGROUND_SERVICE))
        assertTrue(methodsFound.contains(MonitoringMethod.POLLING_FALLBACK))
        
        // Accessibility service should not be available
        val accessibilityStrategy = strategies.find { it.method == MonitoringMethod.ACCESSIBILITY_SERVICE }
        assertNotNull(accessibilityStrategy)
        assertFalse(accessibilityStrategy!!.isAvailable)
    }
    
    @Test
    fun `selectOptimalStrategy returns highest priority available strategy`() = runTest {
        // Given: Device with system hooks available
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns false
        
        // When: Selecting optimal strategy
        val strategy = strategyFactory.selectOptimalStrategy()
        
        // Then: System hooks should be selected (highest priority)
        assertNotNull(strategy)
        assertEquals(MonitoringMethod.SYSTEM_HOOKS, strategy!!.method)
        assertTrue(strategy.isAvailable)
    }
    
    @Test
    fun `selectOptimalStrategy with requirements filters strategies correctly`() = runTest {
        // Given: Device with limited capabilities
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE,
            suBinaryPath = null,
            isRootAccessible = false
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns true
        
        // When: Selecting strategy with background access requirement
        val requiredCapabilities = setOf(MonitoringStrategy.Capability.BACKGROUND_ACCESS)
        val strategy = strategyFactory.selectOptimalStrategy(requiredCapabilities)
        
        // Then: Accessibility service should be selected (meets requirement and available)
        assertNotNull(strategy)
        assertEquals(MonitoringMethod.ACCESSIBILITY_SERVICE, strategy!!.method)
        assertTrue(strategy.hasCapability(MonitoringStrategy.Capability.BACKGROUND_ACCESS))
    }
    
    @Test
    fun `selectOptimalStrategy returns null when no strategy meets requirements`() = runTest {
        // Given: Device with no capabilities
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE,
            suBinaryPath = null,
            isRootAccessible = false
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns false
        
        // When: Selecting strategy with system-level access requirement
        val requiredCapabilities = setOf(MonitoringStrategy.Capability.SYSTEM_LEVEL_ACCESS)
        val strategy = strategyFactory.selectOptimalStrategy(requiredCapabilities)
        
        // Then: No strategy should be selected
        assertNull(strategy)
    }
    
    @Test
    fun `createFallbackChain returns strategies in priority order`() = runTest {
        // Given: Device with mixed capabilities
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = true,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns true
        
        // When: Creating fallback chain
        val fallbackChain = strategyFactory.createFallbackChain()
        
        // Then: Available strategies should be in priority order
        assertTrue(fallbackChain.isNotEmpty())
        
        // Verify order: Xposed -> Accessibility -> Foreground -> Polling
        val expectedOrder = listOf(
            MonitoringMethod.XPOSED_HOOKS,
            MonitoringMethod.ACCESSIBILITY_SERVICE,
            MonitoringMethod.FOREGROUND_SERVICE,
            MonitoringMethod.POLLING_FALLBACK
        )
        
        val actualOrder = fallbackChain.map { it.method }
        assertEquals(expectedOrder, actualOrder)
        
        // All should be available
        assertTrue(fallbackChain.all { it.isAvailable })
    }
    
    @Test
    fun `isMethodAvailable correctly checks individual methods`() = runTest {
        // Given: Device with specific capabilities
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns false
        
        // When/Then: Check individual methods
        assertTrue(strategyFactory.isMethodAvailable(MonitoringMethod.SYSTEM_HOOKS))
        assertFalse(strategyFactory.isMethodAvailable(MonitoringMethod.XPOSED_HOOKS))
        assertFalse(strategyFactory.isMethodAvailable(MonitoringMethod.ACCESSIBILITY_SERVICE))
        assertTrue(strategyFactory.isMethodAvailable(MonitoringMethod.FOREGROUND_SERVICE))
        assertTrue(strategyFactory.isMethodAvailable(MonitoringMethod.POLLING_FALLBACK))
    }
    
    @Test
    fun `getDeviceCapabilities returns comprehensive device information`() = runTest {
        // Given: Device with specific capabilities
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        coEvery { rootDetectionService.isRooted() } returns true
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns true
        every { anyConstructed<AccessibilityPermissionManager>().isServiceActiveAndMonitoring() } returns false
        
        // When: Getting device capabilities
        val capabilities = strategyFactory.getDeviceCapabilities()
        
        // Then: All information should be present
        assertTrue(capabilities.containsKey("android_version"))
        assertTrue(capabilities.containsKey("android_release"))
        assertTrue(capabilities.containsKey("is_rooted"))
        assertTrue(capabilities.containsKey("has_system_hooks"))
        assertTrue(capabilities.containsKey("has_xposed_framework"))
        assertTrue(capabilities.containsKey("has_native_access"))
        assertTrue(capabilities.containsKey("root_method"))
        assertTrue(capabilities.containsKey("accessibility_service_enabled"))
        assertTrue(capabilities.containsKey("accessibility_service_running"))
        assertTrue(capabilities.containsKey("device_manufacturer"))
        assertTrue(capabilities.containsKey("device_model"))
        
        // Verify specific values
        assertEquals(true, capabilities["is_rooted"])
        assertEquals(true, capabilities["has_system_hooks"])
        assertEquals(false, capabilities["has_xposed_framework"])
        assertEquals("MAGISK", capabilities["root_method"])
        assertEquals(true, capabilities["accessibility_service_enabled"])
        assertEquals(false, capabilities["accessibility_service_running"])
    }
    
    @Test
    @Config(sdk = [Build.VERSION_CODES.Q]) // Android 10
    fun `accessibility service gets higher priority on Android 10+`() = runTest {
        // Given: Non-root Android 10+ device
        val rootCapabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE,
            suBinaryPath = null,
            isRootAccessible = false
        )
        coEvery { rootDetectionService.getRootCapabilities() } returns rootCapabilities
        every { anyConstructed<AccessibilityPermissionManager>().isAccessibilityServiceEnabled() } returns true
        
        // When: Creating available strategies
        val strategies = strategyFactory.createAvailableStrategies()
        
        // Then: Accessibility service should have higher priority than foreground service
        val accessibilityStrategy = strategies.find { it.method == MonitoringMethod.ACCESSIBILITY_SERVICE }
        val foregroundStrategy = strategies.find { it.method == MonitoringMethod.FOREGROUND_SERVICE }
        
        assertNotNull(accessibilityStrategy)
        assertNotNull(foregroundStrategy)
        assertTrue(accessibilityStrategy!!.priority > foregroundStrategy!!.priority)
    }
}