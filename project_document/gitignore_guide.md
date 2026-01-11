# ClipboardSync Android .gitignore 配置指南

**更新时间:** 2025-07-29 20:00:00 +08:00  
**版本:** v2.0 (全面更新)  

## 📋 概述

本文档说明了ClipboardSync Android项目的`.gitignore`配置，确保版本控制中不包含不必要的文件。

## 🎯 忽略策略

### 安全原则
- **所有密钥和证书文件** - 防止敏感信息泄露
- **个人配置文件** - 避免开发环境冲突
- **构建产物** - 减少仓库大小，提高克隆速度

### 性能考虑
- **大型缓存目录** - `.gradle/`, `build/`, `.cxx/`
- **临时文件** - 系统生成的临时文件和缓存
- **日志文件** - 运行时生成的日志

## 📂 主要忽略类别

### 1. Android构建文件
```
*.apk           # Android应用包
*.aab           # Android App Bundle
*.dex           # Dalvik字节码
build/          # 构建输出目录
.gradle/        # Gradle缓存
```

### 2. IDE和编辑器配置
```
.idea/          # IntelliJ IDEA配置
.vscode/        # Visual Studio Code
.kiro/          # Kiro开发工具
*.iml           # IntelliJ模块文件
```

### 3. 系统生成文件
```
.DS_Store       # macOS Finder信息
Thumbs.db       # Windows缩略图
*~              # Linux备份文件
```

### 4. 安全敏感文件
```
*.keystore      # Android签名文件
*.jks           # Java密钥库
*.env           # 环境变量文件
local.properties # 本地SDK路径配置
```

### 5. Native开发文件
```
.cxx/           # C++构建缓存
.externalNativeBuild/ # NDK构建输出
cmake-build-*/  # CMake构建目录
```

### 6. 测试和分析文件
```
coverage/       # 代码覆盖率报告
*.hprof         # Java堆转储
test-results/   # 测试结果
```

## 🔍 当前被忽略的文件

根据`git status --ignored`显示，当前被忽略的文件包括：

### 系统文件
- `.DS_Store` - macOS系统文件
- `app/.DS_Store` - 应用目录下的系统文件

### 开发环境
- `.gradle/` - Gradle缓存目录 (包含依赖缓存等)
- `.idea/` - IntelliJ IDEA配置文件
  - `.idea/.name` - 项目名称
  - `.idea/workspace.xml` - 工作区配置
  - `.idea/gradle.xml` - Gradle项目配置
  - `.idea/vcs.xml` - 版本控制配置
  - `.idea/caches/` - IDE缓存
- `.kiro/` - Kiro开发工具配置

### 构建产物
- `build/` - 根目录构建输出
- `app/build/` - 应用构建输出 (138MB)
- `app/.cxx/` - Native C++构建缓存

### 安全文件
- `clipboardsync-release.keystore` - 应用签名密钥库
- `local.properties` - 本地SDK路径配置

## ⚠️ 重要注意事项

### Keystore文件处理
```bash
# keystore文件已被忽略，但需要安全备份
clipboardsync-release.keystore  # 包含应用签名密钥
```

**关键提醒:**
- keystore文件丢失将导致无法更新已发布的应用
- 建议将keystore文件安全备份到多个位置
- 密码信息应单独记录和保存

### 选择性包含
如果需要包含某些被忽略的文件，使用：
```bash
git add -f <filename>  # 强制添加被忽略的文件
```

### 本地配置
`local.properties`包含本地SDK路径，每个开发者的路径可能不同：
```properties
sdk.dir=/Users/username/Library/Android/sdk
ndk.dir=/Users/username/Library/Android/sdk/ndk/25.1.8937393
```

## 🛠️ 维护和更新

### 定期检查
```bash
# 查看当前被忽略的文件
git status --ignored

# 检查大文件
find . -name "*.log" -o -name "*.tmp" -size +10M

# 清理Git缓存（应用新的忽略规则）
git rm -r --cached .
git add .
```

### 添加新的忽略规则
1. 编辑`.gitignore`文件
2. 如果文件已被跟踪，需要先取消跟踪：
   ```bash
   git rm --cached <filename>
   ```
3. 提交更改

## 📊 效果评估

### 仓库大小优化
- **构建目录忽略**: 节省 ~138MB (app/build/)
- **IDE配置忽略**: 节省 ~数MB (.idea/, .gradle/)
- **系统文件忽略**: 避免跨平台冲突

### 安全性提升
- 防止密钥文件意外提交
- 保护本地开发环境配置
- 避免敏感API密钥泄露

## 🔄 最佳实践

1. **定期审查**: 每月检查并更新忽略规则
2. **团队同步**: 确保所有开发者使用相同的忽略配置
3. **文档维护**: 及时更新忽略规则说明
4. **备份策略**: 重要文件（如keystore）单独备份

---

**相关文档:**
- `project_document/keystore_info.md` - Keystore管理指南
- `project_document/device_test_report.md` - 设备测试报告 