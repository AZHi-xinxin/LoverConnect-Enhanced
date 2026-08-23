package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCallbackWatchdogTest {
    @Test
    fun `subscription policy does not require movement`() {
        assertEquals(0f, LocationSamplingPolicy.MIN_DISTANCE_METERS)
        assertEquals(90_000L, LocationSamplingPolicy.INTERVAL_MS)
    }

    @Test
    fun `watchdog requests initial registration`() {
        val watchdog = LocationCallbackWatchdog(staleAfterMs = 100L)

        assertTrue(watchdog.shouldReregister(nowElapsedMs = 1_000L))
    }

    @Test
    fun `registration attempt is not retried before timeout`() {
        val watchdog = LocationCallbackWatchdog(staleAfterMs = 100L)
        watchdog.onRegistrationAttempt(nowElapsedMs = 1_000L)

        assertFalse(watchdog.shouldReregister(nowElapsedMs = 1_099L))
        assertTrue(watchdog.shouldReregister(nowElapsedMs = 1_100L))
    }

    @Test
    fun `raw callback resets stale deadline even when sample is later rejected`() {
        val watchdog = LocationCallbackWatchdog(staleAfterMs = 100L)
        watchdog.onRegistrationAttempt(nowElapsedMs = 1_000L)
        watchdog.onRawCallback(nowElapsedMs = 1_080L)

        assertFalse(watchdog.shouldReregister(nowElapsedMs = 1_179L))
        assertTrue(watchdog.shouldReregister(nowElapsedMs = 1_180L))
    }

    @Test
    fun `clock rollback never triggers immediate retry`() {
        val watchdog = LocationCallbackWatchdog(staleAfterMs = 100L)
        watchdog.onRegistrationAttempt(nowElapsedMs = 1_000L)

        assertFalse(watchdog.shouldReregister(nowElapsedMs = 900L))
    }
}
