# Token自动刷新机制实现指南

**文档创建时间:** 2025-07-29 19:08:13 +08:00  
**作者:** Sun Wukong (AI)  
**适用平台:** Android, iOS, Windows, Web, 其他移动/桌面平台  

## 📋 概述

本指南基于clipboard-sync项目的Token过期问题分析，提供了完整的Token自动刷新机制实现方案。该方案已在iOS和macOS客户端验证有效，可直接应用于Android等其他平台。

### 问题背景
- **问题现象**: 客户端登录约24小时后Token过期，用户被迫重新登录
- **根本原因**: 缺乏自动Token刷新机制
- **解决思路**: 双重保障（预刷新 + 401拦截器）

## 🏗️ 架构设计

### 核心组件
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   客户端应用     │    │  Token管理器     │    │   后端API       │
│                │    │                │    │                │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │   UI层      │ │    │ │ JWT解析器   │ │    │ │ 认证服务    │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │ 网络请求层  │◄├────┤ │ 401拦截器   │◄├────┤ │ 刷新接口    │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │                │
│ │ 安全存储    │ │    │ │ 定时器      │ │    │                │
│ └─────────────┘ │    │ └─────────────┘ │    │                │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### 工作流程
1. **登录成功**: 启动Token管理器
2. **定时检查**: 每10分钟检查Token状态
3. **预刷新**: Token过期前5分钟自动刷新
4. **401拦截**: API请求401时自动刷新并重试
5. **失败处理**: 刷新失败时强制登出

## 📡 后端接口规范

### Token刷新接口规范
在实施客户端Token自动刷新前，确保后端提供以下标准接口：

#### POST /api/v1/auth/refresh
**功能**: 使用refresh_token获取新的access_token

**请求规范**:
```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**成功响应** (200):
```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." // 可选，新的refresh_token
  }
}
```

**失败响应** (401):
```json
{
  "success": false,
  "error": "refresh token expired"
}
```

**重要特性**:
- ✅ **不需要Authorization头**: 刷新接口本身不需要认证
- ✅ **幂等性**: 使用相同refresh_token多次调用应返回一致结果
- ✅ **原子性**: 要么成功返回新Token，要么完全失败
- ✅ **安全性**: refresh_token过期时立即返回401，不允许延期

### JWT Token格式要求
客户端JWT解析依赖以下标准字段：

**Payload结构**:
```json
{
  "user_id": "uuid-string",           // 必需：用户ID
  "device_id": "uuid-string",         // 可选：设备ID UUID格式
  "device_id_str": "string",          // 可选：设备ID 字符串格式
  "exp": 1640995200,                  // 必需：过期时间 Unix timestamp
  "iat": 1640908800,                  // 可选：签发时间 Unix timestamp
  "iss": "clipboard-sync"             // 可选：签发者
}
```

**编码要求**:
- 使用标准JWT格式：`header.payload.signature`
- Payload使用Base64URL编码
- 确保`exp`字段为Unix时间戳（秒）

## 🔧 实现步骤

### Step 1: JWT解析器

#### Android (Java/Kotlin)
```kotlin
// 添加依赖: implementation 'io.jsonwebtoken:jjwt-api:0.11.5'
class JWTParser {
    data class TokenInfo(
        val userId: String,
        val deviceId: String?,
        val expirationTime: Date,
        val issuedAt: Date
    )
    
    companion object {
        fun parseToken(token: String): TokenInfo? {
            return try {
                val parts = token.split(".")
                if (parts.size != 3) return null
                
                val payload = parts[1]
                val decodedBytes = Base64.decode(addPadding(payload), Base64.URL_SAFE or Base64.NO_WRAP)
                val json = JSONObject(String(decodedBytes))
                
                TokenInfo(
                    userId = json.optString("user_id", ""),
                    deviceId = json.optString("device_id"),
                    expirationTime = Date(json.getLong("exp") * 1000),
                    issuedAt = Date(json.optLong("iat", System.currentTimeMillis()) * 1000)
                )
            } catch (e: Exception) {
                null
            }
        }
        
        fun isTokenExpired(token: String): Boolean {
            val tokenInfo = parseToken(token) ?: return true
            return Date() >= tokenInfo.expirationTime
        }
        
        fun isTokenNearExpiry(token: String, minutesBefore: Int = 5): Boolean {
            val tokenInfo = parseToken(token) ?: return true
            val expiryTime = tokenInfo.expirationTime.time
            val currentTime = System.currentTimeMillis()
            val timeUntilExpiry = expiryTime - currentTime
            return timeUntilExpiry > 0 && timeUntilExpiry <= minutesBefore * 60 * 1000
        }
        
        private fun addPadding(base64: String): String {
            val remainder = base64.length % 4
            return if (remainder != 0) {
                base64 + "=".repeat(4 - remainder)
            } else base64
        }
    }
}

enum class TokenStatus {
    VALID, NEAR_EXPIRY, EXPIRED, INVALID;
    
    companion object {
        fun from(token: String?): TokenStatus {
            if (token == null) return INVALID
            return when {
                JWTParser.isTokenExpired(token) -> EXPIRED
                JWTParser.isTokenNearExpiry(token) -> NEAR_EXPIRY
                else -> VALID
            }
        }
    }
}
```

#### React Native (JavaScript)
```javascript
// npm install @react-native-async-storage/async-storage
// npm install react-native-keychain

class JWTParser {
  static parseToken(token) {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return null;
      
      const payload = parts[1];
      const decodedPayload = this.base64UrlDecode(payload);
      const json = JSON.parse(decodedPayload);
      
      return {
        userId: json.user_id || '',
        deviceId: json.device_id,
        expirationTime: new Date(json.exp * 1000),
        issuedAt: new Date((json.iat || Date.now() / 1000) * 1000)
      };
    } catch (error) {
      return null;
    }
  }
  
  static isTokenExpired(token) {
    const tokenInfo = this.parseToken(token);
    if (!tokenInfo) return true;
    return new Date() >= tokenInfo.expirationTime;
  }
  
  static isTokenNearExpiry(token, minutesBefore = 5) {
    const tokenInfo = this.parseToken(token);
    if (!tokenInfo) return true;
    
    const timeUntilExpiry = tokenInfo.expirationTime.getTime() - Date.now();
    return timeUntilExpiry > 0 && timeUntilExpiry <= minutesBefore * 60 * 1000;
  }
  
  static base64UrlDecode(str) {
    str = str.replace(/-/g, '+').replace(/_/g, '/');
    const pad = str.length % 4;
    if (pad) {
      str += '='.repeat(4 - pad);
    }
    return atob(str);
  }
}

const TokenStatus = {
  VALID: 'valid',
  NEAR_EXPIRY: 'nearExpiry', 
  EXPIRED: 'expired',
  INVALID: 'invalid',
  
  from(token) {
    if (!token) return this.INVALID;
    if (JWTParser.isTokenExpired(token)) return this.EXPIRED;
    if (JWTParser.isTokenNearExpiry(token)) return this.NEAR_EXPIRY;
    return this.VALID;
  }
};
```

### Step 2: Token管理器

#### Android (Kotlin)
```kotlin
class TokenManager private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: TokenManager? = null
        
        fun getInstance(context: Context): TokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sharedPrefs = context.getSharedPreferences("auth_tokens", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var refreshTimer: Runnable? = null
    private var isRefreshing = false
    private val checkInterval = 10 * 60 * 1000L // 10分钟
    
    fun startTokenManagement() {
        stopTokenManagement()
        checkAndRefreshIfNeeded()
        scheduleNextCheck()
        Log.d("TokenManager", "🔄 Token管理器已启动")
    }
    
    fun stopTokenManagement() {
        refreshTimer?.let { handler.removeCallbacks(it) }
        refreshTimer = null
        Log.d("TokenManager", "⏹️ Token管理器已停止")
    }
    
    private fun scheduleNextCheck() {
        refreshTimer = Runnable {
            checkAndRefreshIfNeeded()
            scheduleNextCheck()
        }
        handler.postDelayed(refreshTimer!!, checkInterval)
    }
    
    private fun checkAndRefreshIfNeeded() {
        val accessToken = getAccessToken()
        val refreshToken = getRefreshToken()
        
        if (accessToken == null || refreshToken == null) {
            Log.w("TokenManager", "⚠️ Token缺失，需要重新登录")
            logout()
            return
        }
        
        if (isRefreshing) {
            Log.d("TokenManager", "🔄 Token刷新进行中，跳过检查")
            return
        }
        
        when (TokenStatus.from(accessToken)) {
            TokenStatus.NEAR_EXPIRY -> {
                Log.w("TokenManager", "⚠️ Token即将过期，开始预刷新")
                performTokenRefresh(refreshToken)
            }
            TokenStatus.EXPIRED -> {
                Log.e("TokenManager", "❌ Token已过期，强制刷新")
                performTokenRefresh(refreshToken)
            }
            TokenStatus.VALID -> {
                Log.d("TokenManager", "✅ Token有效")
            }
            TokenStatus.INVALID -> {
                Log.e("TokenManager", "❌ Token无效，需要重新登录")
                logout()
            }
        }
    }
    
    private fun performTokenRefresh(refreshToken: String) {
        if (isRefreshing) return
        isRefreshing = true
        
        Log.d("TokenManager", "🔄 开始刷新Token...")
        
        // 调用API刷新Token
        ApiClient.refreshToken(refreshToken) { result ->
            isRefreshing = false
            when (result) {
                is Result.Success -> {
                    Log.d("TokenManager", "✅ Token刷新成功")
                    saveTokens(result.data.accessToken, result.data.refreshToken)
                }
                is Result.Error -> {
                    Log.e("TokenManager", "❌ Token刷新失败: ${result.exception.message}")
                    logout()
                }
            }
        }
    }
    
    private fun getAccessToken(): String? = sharedPrefs.getString("access_token", null)
    private fun getRefreshToken(): String? = sharedPrefs.getString("refresh_token", null)
    
    private fun saveTokens(accessToken: String, refreshToken: String?) {
        sharedPrefs.edit()
            .putString("access_token", accessToken)
            .apply { refreshToken?.let { putString("refresh_token", it) } }
            .apply()
    }
    
    private fun logout() {
        // 清除Token并通知登出
        sharedPrefs.edit().clear().apply()
        // 发送登出广播或回调
    }
}
```

#### React Native (JavaScript)
```javascript
import AsyncStorage from '@react-native-async-storage/async-storage';

class TokenManager {
  static instance = null;
  
  constructor() {
    this.isRefreshing = false;
    this.timer = null;
    this.checkInterval = 10 * 60 * 1000; // 10分钟
  }
  
  static getInstance() {
    if (!TokenManager.instance) {
      TokenManager.instance = new TokenManager();
    }
    return TokenManager.instance;
  }
  
  startTokenManagement() {
    this.stopTokenManagement();
    this.checkAndRefreshIfNeeded();
    this.scheduleNextCheck();
    console.log('🔄 Token管理器已启动');
  }
  
  stopTokenManagement() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    console.log('⏹️ Token管理器已停止');
  }
  
  scheduleNextCheck() {
    this.timer = setInterval(() => {
      this.checkAndRefreshIfNeeded();
    }, this.checkInterval);
  }
  
  async checkAndRefreshIfNeeded() {
    try {
      const accessToken = await AsyncStorage.getItem('access_token');
      const refreshToken = await AsyncStorage.getItem('refresh_token');
      
      if (!accessToken || !refreshToken) {
        console.warn('⚠️ Token缺失，需要重新登录');
        this.logout();
        return;
      }
      
      if (this.isRefreshing) {
        console.log('🔄 Token刷新进行中，跳过检查');
        return;
      }
      
      const tokenStatus = TokenStatus.from(accessToken);
      
      switch (tokenStatus) {
        case TokenStatus.NEAR_EXPIRY:
          console.warn('⚠️ Token即将过期，开始预刷新');
          this.performTokenRefresh(refreshToken);
          break;
        case TokenStatus.EXPIRED:
          console.error('❌ Token已过期，强制刷新');
          this.performTokenRefresh(refreshToken);
          break;
        case TokenStatus.VALID:
          console.log('✅ Token有效');
          break;
        case TokenStatus.INVALID:
          console.error('❌ Token无效，需要重新登录');
          this.logout();
          break;
      }
    } catch (error) {
      console.error('Token检查出错:', error);
    }
  }
  
  async performTokenRefresh(refreshToken) {
    if (this.isRefreshing) return;
    this.isRefreshing = true;
    
    console.log('🔄 开始刷新Token...');
    
    try {
      const response = await ApiClient.refreshToken(refreshToken);
      console.log('✅ Token刷新成功');
      
      await AsyncStorage.setItem('access_token', response.accessToken);
      if (response.refreshToken) {
        await AsyncStorage.setItem('refresh_token', response.refreshToken);
      }
    } catch (error) {
      console.error('❌ Token刷新失败:', error);
      this.logout();
    } finally {
      this.isRefreshing = false;
    }
  }
  
  async logout() {
    await AsyncStorage.multiRemove(['access_token', 'refresh_token']);
    // 通知应用登出
    // EventEmitter.emit('logout');
  }
}
```

### Step 3: 401拦截器

#### Android (OkHttp)
```kotlin
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        
        // 添加Authorization头
        val accessToken = tokenManager.getAccessToken()
        if (accessToken != null) {
            request = request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        }
        
        var response = chain.proceed(request)
        
        // 处理401错误
        if (response.code == 401 && accessToken != null) {
            Log.d("AuthInterceptor", "🔄 收到401错误，尝试刷新Token并重试")
            
            response.close()
            
            // 同步刷新Token
            val refreshToken = tokenManager.getRefreshToken()
            if (refreshToken != null) {
                try {
                    val newTokens = refreshTokenSync(refreshToken)
                    tokenManager.saveTokens(newTokens.accessToken, newTokens.refreshToken)
                    
                    // 重试原始请求
                    request = request.newBuilder()
                        .header("Authorization", "Bearer ${newTokens.accessToken}")
                        .build()
                    response = chain.proceed(request)
                    
                    Log.d("AuthInterceptor", "✅ Token刷新成功，请求重试完成")
                } catch (e: Exception) {
                    Log.e("AuthInterceptor", "❌ Token刷新失败", e)
                    tokenManager.logout()
                }
            } else {
                tokenManager.logout()
            }
        }
        
        return response
    }
    
    private fun refreshTokenSync(refreshToken: String): TokenResponse {
        // 同步执行Token刷新请求
        val request = Request.Builder()
            .url("${BASE_URL}/api/v1/auth/refresh")
            .post(
                RequestBody.create(
                    MediaType.get("application/json"),
                    """{"refresh_token":"$refreshToken"}"""
                )
            )
            .build()
            
        val response = OkHttpClient().newCall(request).execute()
        if (response.isSuccessful) {
            val json = JSONObject(response.body!!.string())
            return TokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", null)
            )
        } else {
            throw Exception("Token refresh failed: ${response.code}")
        }
    }
    
    data class TokenResponse(val accessToken: String, val refreshToken: String?)
}
```

#### React Native (Axios)
```javascript
// npm install axios

import axios from 'axios';

class ApiClient {
  constructor() {
    this.isRefreshing = false;
    this.failedQueue = [];
    
    this.client = axios.create({
      baseURL: 'https://your-api-base-url.com',
      timeout: 10000,
    });
    
    this.setupInterceptors();
  }
  
  setupInterceptors() {
    // 请求拦截器 - 添加Token
    this.client.interceptors.request.use(async (config) => {
      const token = await AsyncStorage.getItem('access_token');
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });
    
    // 响应拦截器 - 处理401
    this.client.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config;
        
        if (error.response?.status === 401 && !originalRequest._retry) {
          if (this.isRefreshing) {
            // 如果正在刷新，将请求加入队列
            return new Promise((resolve, reject) => {
              this.failedQueue.push({ resolve, reject });
            }).then(() => {
              return this.client(originalRequest);
            });
          }
          
          originalRequest._retry = true;
          this.isRefreshing = true;
          
          try {
            console.log('🔄 收到401错误，尝试刷新Token并重试');
            const refreshToken = await AsyncStorage.getItem('refresh_token');
            
            if (!refreshToken) {
              throw new Error('No refresh token');
            }
            
            const response = await this.refreshTokenRequest(refreshToken);
            const { access_token, refresh_token } = response.data;
            
            await AsyncStorage.setItem('access_token', access_token);
            if (refresh_token) {
              await AsyncStorage.setItem('refresh_token', refresh_token);
            }
            
            // 处理队列中的请求
            this.processQueue(null);
            
            console.log('✅ Token刷新成功，重试原始请求');
            return this.client(originalRequest);
            
          } catch (refreshError) {
            console.error('❌ Token刷新失败:', refreshError);
            this.processQueue(refreshError);
            this.logout();
            return Promise.reject(refreshError);
          } finally {
            this.isRefreshing = false;
          }
        }
        
        return Promise.reject(error);
      }
    );
  }
  
  processQueue(error) {
    this.failedQueue.forEach(({ resolve, reject }) => {
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    });
    
    this.failedQueue = [];
  }
  
  async refreshTokenRequest(refreshToken) {
    // 不使用拦截器的直接请求
    return axios.post('/api/v1/auth/refresh', {
      refresh_token: refreshToken
    }, {
      baseURL: this.client.defaults.baseURL,
      timeout: this.client.defaults.timeout,
    });
  }
  
  logout() {
    AsyncStorage.multiRemove(['access_token', 'refresh_token']);
    // 通知应用登出
  }
}
```

## 🔒 安全考虑

### 1. Token存储
- **Android**: 使用SharedPreferences + EncryptedSharedPreferences
- **iOS**: Keychain Services
- **React Native**: react-native-keychain

### 2. 网络安全
- 所有请求使用HTTPS
- Token传输加密
- 避免Token泄露到日志

### 3. 防护措施
- 限制自动重试次数（最多1次）
- 刷新失败立即清除所有Token
- 记录安全事件日志

## 📊 性能优化

### 1. 电池优化
- 后台时减少检查频率
- 使用系统事件触发而非轮询
- 及时释放Timer资源

### 2. 网络优化
- 预刷新减少401错误
- 批量操作避免多次检查
- 网络状态监控

### 3. 内存优化
- JWT解析结果缓存
- 避免重复解析
- 及时清理过期数据

## 🧪 测试策略

### 1. 单元测试
```kotlin
// Android Kotlin示例
@Test
fun testJWTParser() {
    val token = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoidGVzdCIsImV4cCI6MTY0MDk5NTIwMH0.signature"
    val info = JWTParser.parseToken(token)
    assertNotNull(info)
    assertEquals("test", info?.userId)
}

@Test
fun testTokenExpiry() {
    val expiredToken = createExpiredToken()
    assertTrue(JWTParser.isTokenExpired(expiredToken))
}
```

### 2. 集成测试
- 401拦截器功能测试
- Token刷新流程测试
- 网络异常恢复测试

### 3. E2E测试
- 24小时长期运行测试
- 时间模拟过期场景
- 用户体验流程测试

## 📝 实施清单

### Phase 1: 核心功能
- [ ] JWT解析器实现
- [ ] Token状态枚举定义
- [ ] 基础Token管理器

### Phase 2: 自动管理
- [ ] 定时检查机制
- [ ] 预刷新逻辑
- [ ] 安全存储集成

### Phase 3: 网络拦截
- [ ] 401拦截器实现
- [ ] 自动重试逻辑
- [ ] 失败处理机制

### Phase 4: 生命周期
- [ ] 登录启动Token管理
- [ ] 登出停止Token管理
- [ ] 应用生命周期处理

### Phase 5: 测试验证
- [ ] 单元测试覆盖
- [ ] 集成测试验证
- [ ] 长期稳定性测试

## 🚀 部署建议

### 1. 渐进式部署
- 先在测试环境验证
- 小批量用户试用
- 监控关键指标

### 2. 监控指标
- Token刷新成功率
- 401错误频率
- 用户登录持续时间

### 3. 回滚计划
- 保留原有Token处理逻辑
- 功能开关控制
- 快速回滚机制

## 📞 支持与维护

### 常见问题
1. **Q: Token刷新失败怎么办？**
   A: 自动清除Token并引导用户重新登录

2. **Q: 如何处理时区差异？**
   A: 使用UTC时间，客户端本地解析

3. **Q: 如何避免多个请求同时刷新Token？**
   A: 使用刷新标志位和请求队列机制

### 技术支持
- 参考项目：clipboard-sync
- 架构文档：/project_document/architecture/token_refresh_arch_v1.0.md
- 实现示例：iOS/macOS已验证方案

---

**最后更新:** 2025-07-29 19:08:13 +08:00  
**版本:** v1.0  
**状态:** 生产就绪 