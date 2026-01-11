# ClipboardSync Keystore Information

**创建时间:** 2025-07-29 19:47:57 +08:00  
**keystore文件:** clipboardsync-release.keystore  
**用途:** ClipboardSync Android应用发布签名  

## Keystore 详细信息

### 基本信息
- **文件名:** clipboardsync-release.keystore
- **类型:** PKCS12
- **提供者:** SUN
- **密码:** clip123

### 证书信息
- **别名:** clipboardsync
- **密钥算法:** RSA
- **密钥长度:** 2048位
- **签名算法:** SHA256withRSA
- **有效期:** 10000天 (约27年)

### 证书详情
```
Owner: CN=ClipboardSync, OU=Development, O=SIW Tech, L=Shanghai, ST=Shanghai, C=CN
Issuer: CN=ClipboardSync, OU=Development, O=SIW Tech, L=Shanghai, ST=Shanghai, C=CN
Serial number: 47521ce3395164e7
Valid from: Tue Jul 29 19:47:57 CST 2025 
Valid until: Sat Dec 14 19:47:57 CST 2052
```

### 证书指纹
- **SHA1:** C4:27:41:5E:8C:47:D8:03:94:92:7B:10:3A:AC:2F:81:2C:6D:A7:C3
- **SHA256:** 37:9C:C6:9C:8B:E2:AF:3B:F2:EA:3C:F9:C9:40:A6:22:8A:4B:57:F0:06:A5:02:6F:8D:FB:A3:76:9F:A9:3A:CA

## 使用说明

### 自动签名配置 (build.gradle.kts)
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("${project.rootDir}/clipboardsync-release.keystore")
        storePassword = "clip123"
        keyAlias = "clipboardsync"
        keyPassword = "clip123"
    }
}
```

### 手动签名命令
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
    -keystore clipboardsync-release.keystore \
    -storepass clip123 \
    app-release.apk clipboardsync
```

### 验证签名
```bash
jarsigner -verify app-release.apk
```

## 安全注意事项

1. **密码保护:** keystore和密钥使用相同密码 "clip123"
2. **自签名证书:** 仅用于开发和测试，生产环境建议使用CA签发的证书
3. **备份重要性:** keystore文件丢失将无法更新已发布的应用
4. **密码管理:** 生产环境应使用更强的密码并安全存储

## 备份建议

1. 将keystore文件保存到安全的位置
2. 记录所有密码信息
3. 定期验证keystore可用性
4. 考虑使用密码管理器存储敏感信息

## 组织信息

- **CN (Common Name):** ClipboardSync
- **OU (Organizational Unit):** Development  
- **O (Organization):** SIW Tech
- **L (Location):** Shanghai
- **ST (State):** Shanghai
- **C (Country):** CN

---

**注意:** 此keystore仅用于开发和测试目的。生产发布建议使用更严格的安全措施和CA签发的证书。 