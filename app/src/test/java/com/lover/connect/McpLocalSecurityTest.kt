package com.lover.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpLocalSecurityTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun acceptsOnlyPostToExactPrivateEndpoint() {
        assertTrue(McpLocalSecurity.isAuthorizedRequestLine("POST /mcp/$token HTTP/1.1", token))
        assertFalse(McpLocalSecurity.isAuthorizedRequestLine("POST /mcp HTTP/1.1", token))
        assertFalse(McpLocalSecurity.isAuthorizedRequestLine("GET /mcp/$token HTTP/1.1", token))
        assertFalse(McpLocalSecurity.isAuthorizedRequestLine("POST $token HTTP/1.1", token))
        assertFalse(McpLocalSecurity.isAuthorizedRequestLine("POST /mcp/${token.dropLast(1)}0 HTTP/1.1", token))
        assertFalse(McpLocalSecurity.isAuthorizedRequestLine("POST /mcp/$token?x=1 HTTP/1.1", token))
    }
}
