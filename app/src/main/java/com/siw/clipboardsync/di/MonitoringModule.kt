package com.siw.clipboardsync.di

import android.content.ClipboardManager
import android.content.Context
import com.siw.clipboardsync.monitor.AdaptiveTimingOptimizer
import com.siw.clipboardsync.monitor.BatteryOptimizer
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
import com.siw.clipboardsync.monitor.MonitoringStrategyFactory
import com.siw.clipboardsync.monitor.NativeHookManager
import com.siw.clipboardsync.monitor.SystemLevelClipboardMonitor
import com.siw.clipboardsync.monitor.TimingOptimizer
import com.siw.clipboardsync.monitor.XposedHookManager
import com.siw.clipboardsync.monitor.error.ClipboardErrorHandler
import com.siw.clipboardsync.monitor.error.ErrorNotificationManager
import com.siw.clipboardsync.monitor.migration.MonitoringMigrationManager
import com.siw.clipboardsync.monitor.model.MonitoringConfig
import com.siw.clipboardsync.monitor.model.TimingConfig
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing clipboard monitoring dependencies.
 * 
 * Simplified monitoring model:
 * - XPOSED_HOOKS: Full background sync (highest priority)
 * - SHIZUKU: Full background sync without root
 * - FOREGROUND_SYNC: Sync when app comes to foreground (fallback)
 */
@Module
@InstallIn(SingletonComponent::class)
object MonitoringModule {
    
    @Provides
    @Singleton
    fun provideMonitoringConfig(): MonitoringConfig {
        return MonitoringConfig.default()
    }
    
    @Provides
    @Singleton
    fun provideTimingConfig(): TimingConfig {
        return TimingConfig(
            readDelayMs = 100L,
            writeDelayMs = 50L,
            debounceWindowMs = 500L,  // Increased from 200ms to prevent duplicate syncs
            retryDelayMs = 1000L,
            maxRetries = 3,
            powerModeMultiplier = 1.5f
        )
    }
    
    @Provides
    @Singleton
    fun provideTimingOptimizer(
        @ApplicationContext context: Context,
        timingConfig: TimingConfig
    ): TimingOptimizer {
        return AdaptiveTimingOptimizer(context, timingConfig)
    }
    
    @Provides
    @Singleton
    fun provideNativeHookManager(
        @ApplicationContext context: Context,
        rootDetectionService: RootDetectionService
    ): NativeHookManager {
        return NativeHookManager(context, rootDetectionService)
    }
    
    @Provides
    @Singleton
    fun provideXposedHookManager(
        @ApplicationContext context: Context
    ): XposedHookManager {
        return XposedHookManager(context)
    }
    
    @Provides
    @Singleton
    fun provideClipboardManager(
        @ApplicationContext context: Context
    ): ClipboardManager {
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    
    @Provides
    @Singleton
    fun provideSystemLevelClipboardMonitor(
        @ApplicationContext context: Context,
        rootDetectionService: RootDetectionService,
        timingOptimizer: TimingOptimizer,
        nativeHookManager: NativeHookManager,
        xposedHookManager: XposedHookManager
    ): SystemLevelClipboardMonitor {
        return SystemLevelClipboardMonitor(
            context = context,
            rootDetectionService = rootDetectionService,
            timingOptimizer = timingOptimizer,
            nativeHookManager = nativeHookManager,
            xposedHookManager = xposedHookManager
        )
    }
    
    @Provides
    @Singleton
    fun provideBatteryOptimizer(
        @ApplicationContext context: Context
    ): BatteryOptimizer {
        return BatteryOptimizer(context)
    }
    
    @Provides
    @Singleton
    fun provideErrorNotificationManager(
        @ApplicationContext context: Context
    ): ErrorNotificationManager {
        return ErrorNotificationManager(context)
    }
    
    @Provides
    @Singleton
    fun provideAccessibilityPermissionManager(
        @ApplicationContext context: Context
    ): AccessibilityPermissionManager {
        return AccessibilityPermissionManager(context)
    }
    
    @Provides
    @Singleton
    fun provideClipboardErrorHandler(
        @ApplicationContext context: Context,
        rootDetectionService: RootDetectionService,
        accessibilityPermissionManager: AccessibilityPermissionManager,
        notificationManager: ErrorNotificationManager
    ): ClipboardErrorHandler {
        return ClipboardErrorHandler(
            context,
            rootDetectionService,
            accessibilityPermissionManager,
            notificationManager
        )
    }
    
    @Provides
    @Singleton
    fun provideMonitoringStrategyFactory(
        @ApplicationContext context: Context,
        rootDetectionService: RootDetectionService
    ): MonitoringStrategyFactory {
        return MonitoringStrategyFactory(context, rootDetectionService)
    }
    
    @Provides
    @Singleton
    fun provideClipboardMonitorManager(
        @ApplicationContext context: Context,
        strategyFactory: MonitoringStrategyFactory,
        errorHandler: ClipboardErrorHandler,
        systemLevelMonitor: SystemLevelClipboardMonitor,
        monitoringConfig: MonitoringConfig
    ): ClipboardMonitorManager {
        return ClipboardMonitorManager(
            context = context,
            strategyFactory = strategyFactory,
            errorHandler = errorHandler,
            systemLevelMonitor = systemLevelMonitor,
            monitoringConfig = monitoringConfig
        )
    }
    
    @Provides
    @Singleton
    fun provideCpuUsageTracker(): com.siw.clipboardsync.monitor.CpuUsageTracker {
        return com.siw.clipboardsync.monitor.CpuUsageTracker()
    }
    
    @Provides
    @Singleton
    fun provideMonitoringMigrationManager(
        @ApplicationContext context: Context,
        clipboardMonitorManager: ClipboardMonitorManager
    ): MonitoringMigrationManager {
        return MonitoringMigrationManager(context, clipboardMonitorManager)
    }
}
