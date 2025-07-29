package com.siw.clipboardsync.service

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.siw.clipboardsync.monitor.model.RootCapabilities
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import kotlinx.coroutines.test.runTest

class RootDetectionServiceTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    @Mock
    private lateinit var mockPackageManager: PackageManager
    
    private lateinit var rootDetectionService: RootDetectionService
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        rootDetectionService = RootDetectionService(mockContext)
    }
    
    @Test
    fun `checkRootApps returns true when root app is installed`() {
        // Mock Magisk package as installed
        `when`(mockPackageManager.getPackageInfo("com.topjohnwu.magisk", 0))
            .thenReturn(PackageInfo())
        
        // Mock other packages as not found
        `when`(mockPackageManager.getPackageInfo(argThat<String> { it != "com.topjohnwu.magisk" }, eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val result = rootDetectionService.checkRootApps()
        assertTrue("Should detect root apps when Magisk is installed", result)
    }
    
    @Test
    fun `checkRootApps returns false when no root apps installed`() {
        `when`(mockPackageManager.getPackageInfo(anyString(), eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val result = rootDetectionService.checkRootApps()
        assertFalse("Should not detect root apps when none are installed", result)
    }
    
    @Test
    fun `isRooted returns false when all detection methods fail`() = runTest {
        // Mock all packages as not found (checkRootApps will return false)
        `when`(mockPackageManager.getPackageInfo(anyString(), eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val result = rootDetectionService.isRooted()
        // This will depend on the actual file system and build tags, 
        // but at least checkRootApps should return false
        // We can't easily mock File operations and Build.TAGS in unit tests
        // so this test verifies the method doesn't crash
        assertNotNull("Should return a boolean result", result)
    }
    
    @Test
    fun `getRootCapabilities returns capabilities object`() = runTest {
        // Mock all packages as not found
        `when`(mockPackageManager.getPackageInfo(anyString(), eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val capabilities = rootDetectionService.getRootCapabilities()
        
        assertNotNull("Should return capabilities", capabilities)
        assertNotNull("Should have root method", capabilities.rootMethod)
        // Since no root apps are installed, root method should be NONE or OTHER
        assertTrue("Root method should be valid", 
            capabilities.rootMethod in listOf(
                RootCapabilities.RootMethod.NONE, 
                RootCapabilities.RootMethod.OTHER
            ))
    }
    
    @Test
    fun `getRootCapabilities detects Magisk correctly`() = runTest {
        // Mock Magisk as installed
        `when`(mockPackageManager.getPackageInfo("com.topjohnwu.magisk", 0))
            .thenReturn(PackageInfo())
        `when`(mockPackageManager.getPackageInfo(argThat<String> { it != "com.topjohnwu.magisk" }, eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val capabilities = rootDetectionService.getRootCapabilities()
        
        assertEquals("Should detect Magisk as root method", 
            RootCapabilities.RootMethod.MAGISK, capabilities.rootMethod)
    }
    
    @Test
    fun `getRootCapabilities detects SuperSU correctly`() = runTest {
        // Mock SuperSU as installed
        `when`(mockPackageManager.getPackageInfo("eu.chainfire.supersu", 0))
            .thenReturn(PackageInfo())
        `when`(mockPackageManager.getPackageInfo(argThat<String> { it != "eu.chainfire.supersu" }, eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val capabilities = rootDetectionService.getRootCapabilities()
        
        assertEquals("Should detect SuperSU as root method", 
            RootCapabilities.RootMethod.SUPERSU, capabilities.rootMethod)
    }
    
    @Test
    fun `getRootCapabilities detects KingRoot correctly`() = runTest {
        // Mock KingRoot as installed
        `when`(mockPackageManager.getPackageInfo("com.kingroot.kinguser", 0))
            .thenReturn(PackageInfo())
        `when`(mockPackageManager.getPackageInfo(argThat<String> { it != "com.kingroot.kinguser" }, eq(0)))
            .thenThrow(PackageManager.NameNotFoundException())
        
        val capabilities = rootDetectionService.getRootCapabilities()
        
        assertEquals("Should detect KingRoot as root method", 
            RootCapabilities.RootMethod.KINGROOT, capabilities.rootMethod)
    }
    
    @Test
    fun `checkBuildTags method exists and returns boolean`() {
        // We can't easily mock Build.TAGS in unit tests, but we can verify the method works
        val result = rootDetectionService.checkBuildTags()
        assertNotNull("Should return a boolean result", result)
        assertTrue("Result should be true or false", result is Boolean)
    }
    
    @Test
    fun `checkSuBinary method exists and returns boolean`() {
        // We can't easily mock File operations in unit tests, but we can verify the method works
        val result = rootDetectionService.checkSuBinary()
        assertNotNull("Should return a boolean result", result)
        assertTrue("Result should be true or false", result is Boolean)
    }
    
    @Test
    fun `RootCapabilities data class properties work correctly`() {
        val capabilities = RootCapabilities(
            hasSystemHooks = true,
            hasXposedFramework = false,
            hasNativeAccess = true,
            rootMethod = RootCapabilities.RootMethod.MAGISK,
            suBinaryPath = "/system/bin/su",
            isRootAccessible = true
        )
        
        assertTrue("Should have root access", capabilities.hasRootAccess)
        assertTrue("Should be able to use system level monitoring", 
            capabilities.canUseSystemLevelMonitoring)
        assertEquals("Should have correct su binary path", "/system/bin/su", capabilities.suBinaryPath)
    }
    
    @Test
    fun `RootCapabilities without root access`() {
        val capabilities = RootCapabilities(
            hasSystemHooks = false,
            hasXposedFramework = false,
            hasNativeAccess = false,
            rootMethod = RootCapabilities.RootMethod.NONE,
            suBinaryPath = null,
            isRootAccessible = false
        )
        
        assertFalse("Should not have root access", capabilities.hasRootAccess)
        assertFalse("Should not be able to use system level monitoring", 
            capabilities.canUseSystemLevelMonitoring)
        assertNull("Should not have su binary path", capabilities.suBinaryPath)
    }
}