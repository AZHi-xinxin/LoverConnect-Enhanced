package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSafetyRulesTest {
    @Test
    fun `zone label is trimmed collapsed and stripped of invisible controls`() {
        val input = "  健身\u202E房\n  二楼  "

        assertEquals("健身房 二楼", LocationSafetyRules.normalizeZoneLabel(input))
    }

    @Test
    fun `zone label accepts one through twenty four visible characters`() {
        assertTrue(LocationSafetyRules.isValidZoneLabel("A"))
        assertTrue(LocationSafetyRules.isValidZoneLabel("一".repeat(24)))
    }

    @Test
    fun `zone label rejects blank or overlong content`() {
        assertFalse(LocationSafetyRules.isValidZoneLabel(" \u200B\u2060\n "))
        assertFalse(LocationSafetyRules.isValidZoneLabel("一".repeat(25)))
    }

    @Test
    fun `editing the current zone silently resets observation instead of creating departure`() {
        val snapshot = GeofenceSnapshot(
            state = GeofenceState.INSIDE,
            currentZoneId = "custom",
            lastAcceptedSampleAt = 123_000L,
        )

        var reconciled = LocationSafetyRules.reconcileSnapshotAfterZoneChange(
            snapshot,
            changedZoneId = "custom",
            configuredZoneIds = setOf("home", "custom"),
        )
        assertEquals(GeofenceSnapshot(), reconciled)

        val movedZone = SafetyZone("custom", "健身房", 0.0, 0.02, 500)
        val machine = GeofenceStateMachine(
            LocationSafetyConfig(
                zones = listOf(movedZone),
                stableDurationMs = 120_000L,
                minimumSampleSpacingMs = 30_000L,
            )
        )
        val events = mutableListOf<LocationSafetyEvent>()
        listOf(1L, 61_000L, 121_000L).forEach { at ->
            val transition = machine.process(
                reconciled,
                LocationSample(0.0, 0.0, 10f, at),
            )
            reconciled = transition.snapshot
            events += transition.events
        }
        assertEquals(GeofenceState.AWAY, reconciled.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `editing an away origin keeps the real trip but disables replaced coordinates`() {
        val snapshot = GeofenceSnapshot(
            state = GeofenceState.AWAY,
            awaySessionId = "trip-1",
            departedAt = 100_000L,
            originZoneId = "custom",
        )

        val reconciled = LocationSafetyRules.reconcileSnapshotAfterZoneChange(
            snapshot,
            changedZoneId = "custom",
            configuredZoneIds = setOf("home"),
        )

        assertEquals(GeofenceState.AWAY, reconciled.state)
        assertEquals("trip-1", reconciled.awaySessionId)
        assertEquals(null, reconciled.originZoneId)
        assertTrue(reconciled.distanceReminderSent)
    }

    @Test
    fun `adding an unrelated zone leaves the current observation untouched`() {
        val snapshot = GeofenceSnapshot(
            state = GeofenceState.INSIDE,
            currentZoneId = "home",
            lastAcceptedSampleAt = 123_000L,
        )

        assertEquals(
            snapshot,
            LocationSafetyRules.reconcileSnapshotAfterZoneChange(
                snapshot,
                changedZoneId = "custom",
                configuredZoneIds = setOf("home", "custom"),
            ),
        )
    }
}
