# LSPosed Setup Guide for ClipboardSync

## 📋 Prerequisites
- ✅ Rooted Android device
- ✅ LSPosed framework installed
- ✅ ClipboardSync app installed

## 🔧 LSPosed Configuration Steps

### Step 1: Open LSPosed Manager
1. Open the **LSPosed Manager** app on your device
2. If you don't have it, install it from the official repository

### Step 2: Enable ClipboardSync Module
1. Go to the **"Modules"** tab in LSPosed Manager
2. Find **"ClipboardSync"** in the list
3. **Toggle the switch** to enable the module
4. You should see it marked as "Enabled"

### Step 3: Configure Module Scope ⚠️ **IMPORTANT**
1. **Tap on the ClipboardSync module** (not just the toggle)
2. You'll see the "Scope" configuration screen
3. **Check "System Framework (android)"** ✅
   - This is **CRITICAL** - the module must hook into the system framework
   - Do NOT select individual apps - select the system framework
4. The scope should show: `android` (System Framework)

### Step 4: Reboot Device
1. **Reboot your device** - this is required for LSPosed hooks to take effect
2. Wait for the device to fully boot up

### Step 5: Verify Installation
1. Open ClipboardSync app
2. Tap the **Settings icon** (⚙️) in the top-right corner
3. Check the status screen:
   - ✅ **Root Access**: Should show "Yes"
   - ✅ **LSPosed Framework**: Should show "Yes" 
   - ✅ **ClipboardSync Module**: Should show "Yes" (Active)

## 🎯 What to Select in LSPosed

### ✅ CORRECT Configuration:
```
Module: ClipboardSync ✅ Enabled
Scope: 
  ✅ System Framework (android)
```

### ❌ INCORRECT Configurations:
```
❌ Individual apps selected instead of system framework
❌ No scope selected
❌ Only ClipboardSync app selected
❌ Random system apps selected
```

## 🔍 Troubleshooting

### Module Not Working?
1. **Check LSPosed Manager logs**:
   - Go to "Logs" tab in LSPosed Manager
   - Look for ClipboardSync entries
   - Check for any error messages

2. **Verify Module Scope**:
   - Module MUST be scoped to "System Framework (android)"
   - NOT to individual applications

3. **Reboot Again**:
   - Sometimes requires a second reboot
   - Clear LSPosed Manager cache if needed

4. **Check App Status**:
   - Open ClipboardSync → Settings
   - All three status items should be green ✅

### Still Not Working?
1. **Reinstall Module**:
   - Disable module in LSPosed
   - Uninstall ClipboardSync app
   - Reinstall ClipboardSync app
   - Re-enable module with correct scope
   - Reboot

2. **Check LSPosed Version**:
   - Ensure you have a recent version of LSPosed
   - Some older versions may have compatibility issues

## 📊 Expected Performance

### With LSPosed Hook Active:
- ⚡ **Instant clipboard detection** (< 100ms)
- 🔋 **Minimal battery usage** (no polling)
- 🚀 **Real-time synchronization**
- 📱 **Works in background** (bypasses Android 10+ restrictions)

### Without LSPosed (Fallback):
- ⏱️ **Polling-based detection** (1-5 second delay)
- 🔋 **Higher battery usage**
- 📱 **Limited background access** on Android 10+

## 🎉 Success Indicators

When properly configured, you should see:

1. **In ClipboardSync Status Screen**:
   - Root Access: ✅ Yes
   - LSPosed Framework: ✅ Yes  
   - ClipboardSync Module: ✅ Yes (Active)
   - Optimization Level: MAXIMUM
   - Real-time Monitoring: Yes
   - Battery Impact: Minimal

2. **In LSPosed Manager**:
   - ClipboardSync module enabled ✅
   - Scope: System Framework (android) ✅
   - No error logs related to ClipboardSync

3. **Functional Test**:
   - Copy text on another device
   - Should appear instantly on this device
   - No noticeable delay or battery drain

## 📞 Support

If you're still having issues:
1. Check the app's status screen for detailed diagnostics
2. Review LSPosed Manager logs for error messages
3. Ensure you're running a compatible Android version
4. Verify LSPosed framework is properly installed and working

---

**Remember**: The key is selecting **"System Framework (android)"** as the scope, not individual apps!