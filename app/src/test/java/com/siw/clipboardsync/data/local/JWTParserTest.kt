package com.siw.clipboardsync.data.local

import org.junit.Test
import org.junit.Assert.*

/**
 * JWT解析器单元测试
 * 注意：这个测试主要验证逻辑正确性，实际Android环境下的Base64解码会有所不同
 */
class JWTParserTest {
    
    @Test
    fun testTokenStatusEnum() {
        // 测试Token状态枚举基本功能
        assertEquals(TokenStatus.INVALID, TokenStatus.from(null))
        assertEquals(TokenStatus.INVALID, TokenStatus.from(""))
        assertEquals(TokenStatus.INVALID, TokenStatus.from("invalid-token"))
    }
    
    @Test
    fun testJWTParserValidation() {
        // 测试基本的Token格式验证
        assertNull(JWTParser.parseToken(""))
        assertNull(JWTParser.parseToken("invalid"))
        assertNull(JWTParser.parseToken("header.payload"))
        
        // 测试Token过期检查的默认行为
        assertTrue(JWTParser.isTokenExpired("invalid-token"))
        assertTrue(JWTParser.isTokenNearExpiry("invalid-token"))
        assertEquals(0, JWTParser.getTimeUntilExpiry("invalid-token"))
    }
} 