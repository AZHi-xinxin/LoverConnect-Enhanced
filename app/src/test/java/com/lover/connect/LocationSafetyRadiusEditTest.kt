package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationSafetyRadiusEditTest {
    private val home = SafetyZone("home", "家", 0.0, 0.0, 500)
    private val work = SafetyZone("work", "工作", 0.0, 0.1, 800)
    private val custom = SafetyZone("custom", "健身房", 0.1, 0.0, 600)

    @Test
    fun `the same fence accepts repeated radius edits while preserving its center and all other settings`() {
        val original = LocationSafetyConfig(
            zones = listOf(home, work, custom),
            secondReminderMeters = 7_000,
            validAccuracyMeters = 80f,
            secondReminderDelayMs = 1_200_000L,
        )
        var saved = original

        listOf(900, 300, 2_000, 200).forEach { radius ->
            saved = saved.withZoneRadius("home", radius)
            assertEquals(
                original.copy(zones = listOf(home.copy(radiusMeters = radius), work, custom)),
                saved,
            )
        }
        assertEquals(500, original.zones.first().radiusMeters)
    }

    @Test
    fun `editing another fence keeps the previously saved radius`() {
        val saved = LocationSafetyConfig(zones = listOf(home, work, custom))
            .withZoneRadius("home", 900)
            .withZoneRadius("custom", 1_200)
            .withZoneRadius("home", 300)

        assertEquals(
            listOf(home.copy(radiusMeters = 300), work, custom.copy(radiusMeters = 1_200)),
            saved.zones,
        )
    }

    @Test
    fun `invalid radius or a deleted fence cannot replace the saved configuration`() {
        val saved = LocationSafetyConfig(zones = listOf(home))
        listOf(0, 199, 2_001).forEach { radius ->
            assertThrows(IllegalArgumentException::class.java) {
                saved.withZoneRadius("home", radius)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            saved.withZoneRadius("custom", 600)
        }
        assertEquals(listOf(home), saved.zones)
    }

    @Test
    fun `shrinking an occupied fence silently recalibrates instead of reporting a departure`() {
        val saved = LocationSafetyConfig(zones = listOf(home)).withZoneRadius("home", 200)
        var snapshot = LocationSafetyRules.reconcileSnapshotAfterZoneChange(
            GeofenceSnapshot(state = GeofenceState.INSIDE, currentZoneId = "home"),
            changedZoneId = "home",
            configuredZoneIds = setOf("home"),
            centerChanged = false,
        )
        val machine = GeofenceStateMachine(saved)
        listOf(1L, 30_001L, 60_001L).forEach { at ->
            // Roughly 445 m from the unchanged center: inside the old fence, outside the new one.
            val transition = machine.process(snapshot, LocationSample(0.0, 0.004, 10f, at))
            snapshot = transition.snapshot
            assertTrue(transition.events.isEmpty())
        }
        assertEquals(GeofenceState.AWAY, snapshot.state)
        assertEquals(null, snapshot.awaySessionId)
    }

    @Test
    fun `radius edit during an existing trip preserves its origin and distance reminder`() {
        val snapshot = GeofenceSnapshot(
            state = GeofenceState.AWAY,
            awaySessionId = "trip-1",
            departedAt = 100_000L,
            originZoneId = "home",
        )
        assertEquals(
            snapshot,
            LocationSafetyRules.reconcileSnapshotAfterZoneChange(
                snapshot,
                changedZoneId = "home",
                configuredZoneIds = setOf("home"),
                centerChanged = false,
            ),
        )
    }

    @Test
    fun `reload reconciles a stale callback written after the manager reset`() {
        val originalConfig = LocationSafetyConfig(zones = listOf(home))
        val editedConfig = originalConfig.withZoneRadius("home", 200)
        val oldServiceSnapshot = GeofenceSnapshot(
            state = GeofenceState.INSIDE,
            currentZoneId = "home",
            lastAcceptedSampleAt = 1L,
        )
        var persisted = LocationSafetyRules.reconcileSnapshotAfterZoneChange(
            oldServiceSnapshot,
            changedZoneId = "home",
            configuredZoneIds = setOf("home"),
            centerChanged = false,
        )
        assertEquals(GeofenceState.UNKNOWN, persisted.state)

        // Deterministic order: UI saves, an already queued old callback writes,
        // then ACTION_START reloads. No physical movement occurs in this sequence.
        persisted = GeofenceStateMachine(originalConfig).process(
            oldServiceSnapshot,
            LocationSample(0.0, 0.004, 10f, 30_001L),
        ).snapshot
        assertEquals(GeofenceState.INSIDE, persisted.state)
        val newMachine = GeofenceStateMachine(editedConfig)

        fun observe(initial: GeofenceSnapshot): Pair<GeofenceSnapshot, List<LocationSafetyEvent>> {
            var snapshot = initial
            val events = mutableListOf<LocationSafetyEvent>()
            listOf(60_001L, 90_001L, 120_001L).forEach { at ->
                val transition = newMachine.process(snapshot, LocationSample(0.0, 0.004, 10f, at))
                snapshot = transition.snapshot
                events += transition.events
            }
            return snapshot to events
        }

        // This control reproduces the false departure if reload trusts the overwritten store.
        assertTrue(observe(persisted).second.any { it.type == LocationSafetyEventType.DEPARTED })
        val reloaded = LocationSafetyRules.reconcileSnapshotAfterZoneChange(
            persisted,
            changedZoneId = "home",
            configuredZoneIds = editedConfig.zones.map { it.id }.toSet(),
            centerChanged = false,
        )
        val (finalSnapshot, events) = observe(reloaded)
        assertTrue(events.isEmpty())
        assertEquals(GeofenceState.AWAY, finalSnapshot.state)
        assertEquals(null, finalSnapshot.awaySessionId)
    }

    @Test
    fun `ordinary service reload without a zone edit preserves the snapshot`() {
        val snapshot = GeofenceSnapshot(
            state = GeofenceState.EXIT_PENDING,
            currentZoneId = "home",
            candidateZoneId = "__away__",
            candidateSince = 100_000L,
            candidateSamples = 2,
            lastAcceptedSampleAt = 130_000L,
        )
        assertEquals(
            snapshot,
            LocationSafetyRules.reconcileSnapshotAfterZoneChange(
                snapshot,
                changedZoneId = null,
                configuredZoneIds = setOf("home"),
            ),
        )
    }

    @Test
    fun `changing a pending return radius requires fresh confirmation without losing the trip`() {
        val snapshot = GeofenceSnapshot(
            state = GeofenceState.RETURN_PENDING,
            awaySessionId = "trip-1",
            departedAt = 100_000L,
            originZoneId = "home",
            candidateZoneId = "home",
            candidateSince = 200_000L,
            candidateSamples = 2,
        )
        val reconciled = LocationSafetyRules.reconcileSnapshotAfterZoneChange(
            snapshot,
            changedZoneId = "home",
            configuredZoneIds = setOf("home"),
            centerChanged = false,
        )
        assertEquals(GeofenceState.AWAY, reconciled.state)
        assertEquals("trip-1", reconciled.awaySessionId)
        assertEquals("home", reconciled.originZoneId)
        assertEquals(null, reconciled.candidateZoneId)
        assertEquals(0, reconciled.candidateSamples)
    }
}
