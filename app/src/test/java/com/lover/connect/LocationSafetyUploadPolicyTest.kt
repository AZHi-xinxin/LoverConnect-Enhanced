package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSafetyUploadPolicyTest {
    @Test
    fun `only successful HTTP acknowledgements are delivered`() {
        assertEquals(
            LocationUploadDisposition.DELIVERED,
            LocationSafetyUploadPolicy.disposition(200)
        )
        assertEquals(
            LocationUploadDisposition.DELIVERED,
            LocationSafetyUploadPolicy.disposition(202)
        )
        assertEquals(
            LocationUploadDisposition.RETRY,
            LocationSafetyUploadPolicy.disposition(401)
        )
        assertEquals(
            LocationUploadDisposition.RETRY,
            LocationSafetyUploadPolicy.disposition(429)
        )
        assertEquals(
            LocationUploadDisposition.RETRY,
            LocationSafetyUploadPolicy.disposition(503)
        )
        assertEquals(
            LocationUploadDisposition.REJECTED,
            LocationSafetyUploadPolicy.disposition(400)
        )
    }

    @Test
    fun `wire names match the server contract`() {
        assertEquals(
            "zone_exit_confirmed",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.DEPARTED)
        )
        assertEquals(
            "report_acknowledged",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.REPORT_ACKNOWLEDGED)
        )
        assertEquals(
            "location_degraded",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.LOCATION_DEGRADED)
        )
        assertEquals(
            "tracking_paused",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.TRACKING_PAUSED)
        )
    }

    @Test
    fun `retry delay is bounded`() {
        assertEquals(60_000L, LocationSafetyUploadPolicy.retryDelayMs(1))
        assertEquals(120_000L, LocationSafetyUploadPolicy.retryDelayMs(2))
        assertEquals(300_000L, LocationSafetyUploadPolicy.retryDelayMs(3))
        assertEquals(900_000L, LocationSafetyUploadPolicy.retryDelayMs(99))
    }
}
