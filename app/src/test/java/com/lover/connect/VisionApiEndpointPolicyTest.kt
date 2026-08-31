package com.lover.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionApiEndpointPolicyTest {
    @Test
    fun `public vision endpoints require https`() {
        assertTrue(VisionApiEndpointPolicy.isAllowed("https://api.example.com/v1/chat/completions"))
        assertFalse(VisionApiEndpointPolicy.isAllowed("http://api.example.com/v1/chat/completions"))
        assertFalse(VisionApiEndpointPolicy.isAllowed("http://192.168.1.5:8080/v1/chat/completions"))
        assertFalse(VisionApiEndpointPolicy.isAllowed("http://100.64.0.1:8080/v1/chat/completions"))
    }

    @Test
    fun `same-device loopback may use http`() {
        assertTrue(VisionApiEndpointPolicy.isAllowed("http://127.0.0.1:8080/v1/chat/completions"))
        assertTrue(VisionApiEndpointPolicy.isAllowed("http://localhost:8080/v1/chat/completions"))
        assertTrue(VisionApiEndpointPolicy.isAllowed("http://[::1]:8080/v1/chat/completions"))
    }

    @Test
    fun `credentials fragments and malformed urls are rejected`() {
        assertFalse(VisionApiEndpointPolicy.isAllowed("https://user:pass@example.com/v1/chat/completions"))
        assertFalse(VisionApiEndpointPolicy.isAllowed("https://example.com/v1/chat/completions#secret"))
        assertFalse(VisionApiEndpointPolicy.isAllowed("not a url"))
        assertFalse(VisionApiEndpointPolicy.isAllowed(""))
    }
}
