# 客户端实现指南

## 1. 客户端架构总览

所有客户端都采用相同的架构模式：
- **剪贴板监听模块**：监听系统剪贴板变化
- **网络通信模块**：与服务器进行HTTP/WebSocket通信
- **数据同步模块**：处理本地与云端数据同步
- **后台服务模块**：保持应用在后台运行
- **UI管理模块**：系统托盘/状态栏界面

## 2. 同步机制详解

### 2.1 双向同步流程

**本地→云端（上传）：**
```
本地剪贴板变化 → 客户端监听 → 时间戳去重 → HTTP API上传 → 服务器存储 → WebSocket广播
```

**云端→本地（下载）：**  
```
服务器WebSocket推送 → 客户端收到通知 → HTTP API拉取 → 更新本地剪贴板 → 标记避免循环
```

### 2.2 冲突避免机制

1. **时间戳去重**：1秒内的变化忽略，避免循环同步
2. **设备标识**：通过device_id识别，避免同步回自己
3. **内容哈希**：相同内容不重复同步
4. **用户确认**：敏感平台（如iOS）需要用户确认

## 3. Android 客户端（LSPosed Hook）

### 3.1 LSPosed Hook 模块

```kotlin
// app/src/main/java/com/example/clipboardsync/xposed/ClipboardHook.kt
@HookClass(ClipboardManager::class)
class ClipboardHook : BaseHook() {
    
    @MethodHook("setPrimaryClip")
    fun hookSetPrimaryClip(param: MethodParam) {
        val clipData = param.args[0] as ClipData
        val context = param.thisObject as ClipboardManager
        
        // 调用原方法
        param.callOriginal()
        
        // 发送到同步服务
        sendToSyncService(context, clipData)
    }
    
    private fun sendToSyncService(context: Context, clipData: ClipData) {
        val intent = Intent(CLIPBOARD_CHANGED_ACTION).apply {
            putExtra("clip_data", clipData)
            putExtra("timestamp", System.currentTimeMillis())
        }
        context.sendBroadcast(intent)
    }
}
```

### 3.2 保活策略
- 前台服务 + 通知栏
- JobScheduler 定时唤醒
- 监听系统广播重启
- 多进程守护

## 4. macOS 客户端

### 4.1 剪贴板监听

```swift
// Sources/ClipboardSync/ClipboardMonitor.swift
import Cocoa

class ClipboardMonitor: ObservableObject {
    private var changeCount: Int
    private var timer: Timer?
    private let pasteboard = NSPasteboard.general
    
    @Published var isMonitoring = false
    
    init() {
        self.changeCount = pasteboard.changeCount
    }
    
    func startMonitoring() {
        isMonitoring = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.checkForChanges()
        }
    }
    
    func stopMonitoring() {
        isMonitoring = false
        timer?.invalidate()
        timer = nil
    }
    
    private func checkForChanges() {
        let currentChangeCount = pasteboard.changeCount
        guard currentChangeCount != changeCount else { return }
        
        changeCount = currentChangeCount
        processClipboardChange()
    }
    
    private func processClipboardChange() {
        guard let items = pasteboard.pasteboardItems else { return }
        
        for item in items {
            if let string = item.string(forType: .string) {
                handleTextContent(string)
            } else if let imageData = item.data(forType: .png) {
                handleImageContent(imageData)
            } else if let fileURL = item.string(forType: .fileURL) {
                handleFileContent(URL(string: fileURL))
            }
        }
    }
    
    private func handleTextContent(_ text: String) {
        Task {
            await SyncManager.shared.syncText(text)
        }
    }
    
    private func handleImageContent(_ imageData: Data) {
        Task {
            await SyncManager.shared.syncImage(imageData)
        }
    }
}
```

### 4.2 菜单栏应用

```swift
// Sources/ClipboardSync/MenuBarApp.swift
import SwiftUI

@main
struct ClipboardSyncApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var clipboardMonitor = ClipboardMonitor()
    @StateObject private var syncManager = SyncManager.shared
    
    var body: some Scene {
        MenuBarExtra("Clipboard Sync", systemImage: "doc.on.clipboard") {
            MenuBarView()
                .environmentObject(clipboardMonitor)
                .environmentObject(syncManager)
        }
        .menuBarExtraStyle(.window)
    }
}

// Sources/ClipboardSync/MenuBarView.swift
struct MenuBarView: View {
    @EnvironmentObject var clipboardMonitor: ClipboardMonitor
    @EnvironmentObject var syncManager: SyncManager
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // 状态显示
            HStack {
                Circle()
                    .fill(syncManager.isConnected ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(syncManager.isConnected ? "已连接" : "未连接")
                    .font(.caption)
            }
            
            Divider()
            
            // 功能按钮
            Button(action: toggleMonitoring) {
                HStack {
                    Image(systemName: clipboardMonitor.isMonitoring ? "pause.circle" : "play.circle")
                    Text(clipboardMonitor.isMonitoring ? "暂停监听" : "开始监听")
                }
            }
            
            Button("设置") {
                openSettings()
            }
            
            Divider()
            
            Button("退出") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding()
        .frame(width: 200)
    }
    
    private func toggleMonitoring() {
        if clipboardMonitor.isMonitoring {
            clipboardMonitor.stopMonitoring()
        } else {
            clipboardMonitor.startMonitoring()
        }
    }
}
```

## 5. Windows 客户端

### 5.1 剪贴板监听

```csharp
// Services/ClipboardMonitor.cs
using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

public class ClipboardMonitor : IDisposable
{
    private const int WM_CLIPBOARDUPDATE = 0x031D;
    private readonly Window _window;
    private HwndSource _source;
    
    public event EventHandler<ClipboardChangedEventArgs> ClipboardChanged;
    
    public ClipboardMonitor()
    {
        _window = new Window();
        _window.WindowStyle = WindowStyle.None;
        _window.ShowInTaskbar = false;
        _window.Visibility = Visibility.Hidden;
        _window.SourceInitialized += OnSourceInitialized;
        _window.Show();
    }
    
    private void OnSourceInitialized(object sender, EventArgs e)
    {
        _source = PresentationSource.FromVisual(_window) as HwndSource;
        _source?.AddHook(WndProc);
        NativeMethods.AddClipboardFormatListener(_source.Handle);
    }
    
    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_CLIPBOARDUPDATE)
        {
            OnClipboardChanged();
            handled = true;
        }
        return IntPtr.Zero;
    }
    
    private void OnClipboardChanged()
    {
        if (Clipboard.ContainsText())
        {
            var text = Clipboard.GetText();
            ClipboardChanged?.Invoke(this, new ClipboardChangedEventArgs
            {
                ContentType = ClipboardContentType.Text,
                Content = text
            });
        }
        else if (Clipboard.ContainsImage())
        {
            var image = Clipboard.GetImage();
            ClipboardChanged?.Invoke(this, new ClipboardChangedEventArgs
            {
                ContentType = ClipboardContentType.Image,
                ImageData = ImageToByteArray(image)
            });
        }
        else if (Clipboard.ContainsFileDropList())
        {
            var files = Clipboard.GetFileDropList();
            ClipboardChanged?.Invoke(this, new ClipboardChangedEventArgs
            {
                ContentType = ClipboardContentType.File,
                FilePaths = files.Cast<string>().ToArray()
            });
        }
    }
    
    public void Dispose()
    {
        if (_source != null)
        {
            NativeMethods.RemoveClipboardFormatListener(_source.Handle);
            _source.RemoveHook(WndProc);
        }
        _window?.Close();
    }
}

// Native methods
internal static class NativeMethods
{
    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool AddClipboardFormatListener(IntPtr hwnd);
    
    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
}
```

### 5.2 系统托盘应用

```csharp
// Views/TrayIcon.cs
using System.Drawing;
using System.Windows.Forms;

public class TrayIcon : IDisposable
{
    private NotifyIcon _notifyIcon;
    private readonly SyncManager _syncManager;
    
    public TrayIcon(SyncManager syncManager)
    {
        _syncManager = syncManager;
        InitializeTrayIcon();
    }
    
    private void InitializeTrayIcon()
    {
        _notifyIcon = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "Clipboard Sync",
            Visible = true
        };
        
        var contextMenu = new ContextMenuStrip();
        contextMenu.Items.Add("状态: 已连接", null, OnStatusClick);
        contextMenu.Items.Add(new ToolStripSeparator());
        contextMenu.Items.Add("暂停同步", null, OnToggleSync);
        contextMenu.Items.Add("设置", null, OnSettingsClick);
        contextMenu.Items.Add(new ToolStripSeparator());
        contextMenu.Items.Add("退出", null, OnExitClick);
        
        _notifyIcon.ContextMenuStrip = contextMenu;
        _notifyIcon.DoubleClick += OnDoubleClick;
    }
    
    private void OnToggleSync(object sender, EventArgs e)
    {
        if (_syncManager.IsMonitoring)
        {
            _syncManager.StopMonitoring();
            ((ToolStripMenuItem)sender).Text = "开始同步";
        }
        else
        {
            _syncManager.StartMonitoring();
            ((ToolStripMenuItem)sender).Text = "暂停同步";
        }
    }
    
    public void Dispose()
    {
        _notifyIcon?.Dispose();
    }
}
```

## 6. iOS 客户端（受限模式）

### 6.1 剪贴板访问限制
- iOS 14+ 需要用户确认访问剪贴板
- 定时检查剪贴板变化
- 弹窗确认同步操作
- App Group 数据共享

### 6.2 解决方案
```swift
func requestClipboardAccess() {
    let alert = UIAlertController(
        title: "访问剪贴板",
        message: "检测到剪贴板内容变化，是否同步？",
        preferredStyle: .alert
    )
    // 用户确认后执行同步
}
```

## 7. 统一网络通信

### 7.1 WebSocket 客户端
- 断线重连机制
- 心跳检测
- 消息队列
- 状态管理

### 7.2 HTTP API 调用
- 认证token管理
- 请求重试机制
- 错误处理
- 文件上传/下载

## 8. 数据类型支持

### 8.1 文本内容
- 纯文本
- 富文本（RTF）
- HTML内容
- 代码片段

### 8.2 图片内容  
- PNG/JPG/GIF格式
- 自动压缩
- 尺寸限制
- 格式转换

### 8.3 文件内容
- 任意文件类型
- 大小限制（默认10MB）
- 分片上传
- 断点续传

## 9. 安全考虑

### 9.1 传输安全
- HTTPS/WSS 加密传输
- 证书验证
- 中间人攻击防护

### 9.2 存储安全
- 本地敏感数据加密
- KeyChain/Credential Manager
- 临时文件清理

### 9.3 隐私保护
- 敏感内容过滤
- 用户隐私设置
- 数据定期清理

## 10. 性能优化

### 10.1 资源占用
- 最小化CPU使用
- 内存泄漏防护
- 电池优化

### 10.2 网络优化
- 数据压缩
- 增量同步
- 本地缓存

## 11. 用户体验

### 11.1 状态反馈
- 实时连接状态
- 同步进度显示
- 错误提示

### 11.2 用户设置
- 同步开关
- 文件大小限制
- 自动启动设置
- 快捷键配置

## 12. 开发和调试

### 12.1 开发环境
- 各平台IDE配置
- 调试工具使用
- 日志系统

### 12.2 测试策略
- 单元测试
- 集成测试
- 跨平台测试
- 性能测试

这个实现指南确保了各平台客户端能够实现统一的剪贴板实时同步功能。 