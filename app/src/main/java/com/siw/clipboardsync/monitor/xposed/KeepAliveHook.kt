package com.siw.clipboardsync.monitor.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Simplified Xposed module for keeping ClipboardSync alive.
 * 
 * Only hooks forceStopPackage to prevent manual force stop.
 * Removed aggressive hooks that could cause system performance issues.
 */
class KeepAliveHook : IXposedHookLoadPackage {
    
    companion object {
        private const val TAG = "KeepAliveHook"
        private const val APP_PACKAGE = "com.siw.clipboardsync"
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook system framework
        if (lpparam.packageName == "android") {
            hookForceStopPackage(lpparam.classLoader)
        }
    }
    
    /**
     * Hook forceStopPackage to prevent our app from being force stopped
     */
    private fun hookForceStopPackage(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService",
                classLoader
            )
            
            XposedHelpers.findAndHookMethod(
                amsClass,
                "forceStopPackage",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String
                        if (packageName == APP_PACKAGE) {
                            XposedBridge.log("$TAG: Blocked forceStopPackage for $APP_PACKAGE")
                            param.result = null
                        }
                    }
                }
            )
            
            XposedBridge.log("$TAG: Hooked forceStopPackage")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook forceStopPackage: ${e.message}")
        }
    }
}
