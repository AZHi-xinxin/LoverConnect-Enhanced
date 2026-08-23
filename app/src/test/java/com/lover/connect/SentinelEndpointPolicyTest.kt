package com.lover.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelEndpointPolicyTest {
    @Test
    fun allowsHttpsAndPrivateHttp() {
        assertTrue(SentinelEndpointPolicy.isAllowed("https://example.invalid/loverconnect/alert"))
        assertTrue(SentinelEndpointPolicy.isAllowed("http://127.0.0.1:8790/loverconnect/alert"))
        assertTrue(SentinelEndpointPolicy.isAllowed("http://192.168.1.5:8790/loverconnect/alert"))
        assertTrue(SentinelEndpointPolicy.isAllowed("http://100.64.0.1:8790/loverconnect/alert"))
        assertTrue(SentinelEndpointPolicy.isAllowed("http://device.example.ts.net:8790/loverconnect/alert"))
        assertTrue(
            SentinelEndpointPolicy.locationEventsUrl(
                "http://100.64.0.1:8790/loverconnect/alert"
            ) == "http://100.64.0.1:8790/loverconnect/v1/location-events"
        )
        assertTrue(
            SentinelEndpointPolicy.locationEventsUrl(
                "https://example.invalid/loverconnect/v1/location-events"
            ) == "https://example.invalid/loverconnect/v1/location-events"
        )
    }

    @Test
    fun rejectsUnsafeOrMalformedEndpoints() {
        assertFalse(SentinelEndpointPolicy.isAllowed("http://example.com/loverconnect/alert"))
        assertFalse(SentinelEndpointPolicy.isAllowed("http://fcorp.example/loverconnect/alert"))
        assertFalse(SentinelEndpointPolicy.isAllowed("ftp://192.168.1.5/file"))
        assertFalse(SentinelEndpointPolicy.isAllowed("https://user:pass@example.com/alert"))
        assertFalse(SentinelEndpointPolicy.isAllowed("not a url"))
        assertFalse(SentinelEndpointPolicy.isAllowed(""))
        assertTrue(SentinelEndpointPolicy.locationEventsUrl("https://example.com/other") == null)
    }
}
