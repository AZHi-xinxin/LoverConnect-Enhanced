package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceStateMachineTest {
    private val home = SafetyZone("home", "家", 0.0, 0.0, 500)
    private val work = SafetyZone("work", "工作", 0.0, 0.01, 500)
    private val config = LocationSafetyConfig(
        zones = listOf(home, work),
        stableDurationMs = 120_000L,
        minimumSampleSpacingMs = 30_000L
    )
    private val machine = GeofenceStateMachine(config)

    @Test
    fun `initial calibration inside is silent`() {
        var snapshot = GeofenceSnapshot()
        val events = mutableListOf<LocationSafetyEvent>()
        listOf(1L, 61_000L, 121_000L).forEach { at ->
            val transition = machine.process(snapshot, sample(0.0, 0.0, at))
            snapshot = transition.snapshot
            events += transition.events
        }
        assertEquals(GeofenceState.INSIDE, snapshot.state)
        assertEquals("home", snapshot.currentZoneId)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `gps loss and inaccurate samples never become departure`() {
        var snapshot = calibratedInside()
        repeat(5) { index ->
            val bad = LocationSample(0.0, 0.02, 400f, 200_000L + index * 60_000L)
            val transition = machine.process(snapshot, bad)
            assertFalse(transition.accepted)
            assertTrue(transition.events.isEmpty())
            snapshot = transition.snapshot
        }
        assertEquals(GeofenceState.INSIDE, snapshot.state)
    }

    @Test
    fun `exit candidate is cancelled when user returns inside`() {
        var snapshot = calibratedInside()
        snapshot = machine.process(snapshot, sample(0.0, -0.01, 200_000L)).snapshot
        assertEquals(GeofenceState.EXIT_PENDING, snapshot.state)

        val returned = machine.process(snapshot, sample(0.0, 0.0, 260_000L))
        assertEquals(GeofenceState.INSIDE, returned.snapshot.state)
        assertTrue(returned.events.isEmpty())
    }

    @Test
    fun `stable exit emits one departure and stable return emits one arrival`() {
        var snapshot = calibratedInside()
        val departureEvents = mutableListOf<LocationSafetyEvent>()
        listOf(200_000L, 260_000L, 320_000L).forEach { at ->
            val transition = machine.process(snapshot, sample(0.0, -0.01, at))
            snapshot = transition.snapshot
            departureEvents += transition.events
        }
        assertEquals(GeofenceState.AWAY, snapshot.state)
        assertEquals(1, departureEvents.size)
        assertEquals(LocationSafetyEventType.DEPARTED, departureEvents.single().type)
        assertNotNull(snapshot.awaySessionId)

        val arrivalEvents = mutableListOf<LocationSafetyEvent>()
        listOf(400_000L, 460_000L, 520_000L).forEach { at ->
            val transition = machine.process(snapshot, sample(0.0, 0.0, at))
            snapshot = transition.snapshot
            arrivalEvents += transition.events
        }
        assertEquals(GeofenceState.INSIDE, snapshot.state)
        assertEquals("home", snapshot.currentZoneId)
        assertEquals(LocationSafetyEventType.ARRIVED, arrivalEvents.single().type)
    }

    @Test
    fun `second reminder waits fifteen minutes and only fires once`() {
        var snapshot = calibratedInside()
        listOf(200_000L, 260_000L, 320_000L).forEach { at ->
            snapshot = machine.process(snapshot, sample(0.0, -0.01, at)).snapshot
        }
        val tooEarly = machine.process(snapshot, sample(0.0, -0.06, 1_000_000L))
        assertTrue(tooEarly.events.isEmpty())
        snapshot = tooEarly.snapshot

        val due = machine.process(snapshot, sample(0.0, -0.06, 1_240_000L))
        assertEquals(LocationSafetyEventType.DISTANCE_REMINDER, due.events.single().type)
        assertTrue(due.snapshot.distanceReminderSent)

        val repeat = machine.process(due.snapshot, sample(0.0, -0.07, 1_300_000L))
        assertTrue(repeat.events.isEmpty())
    }

    @Test
    fun `overlapping zones choose the nearest center`() {
        val closeWork = work.copy(longitude = 0.001)
        val overlapping = GeofenceStateMachine(
            config.copy(zones = listOf(home, closeWork))
        )
        var snapshot = GeofenceSnapshot()
        listOf(1L, 61_000L, 121_000L).forEach { at ->
            snapshot = overlapping.process(snapshot, sample(0.0, 0.0009, at)).snapshot
        }
        assertEquals(GeofenceState.INSIDE, snapshot.state)
        assertEquals("work", snapshot.currentZoneId)
    }

    @Test
    fun `initial calibration while already away is silent`() {
        var snapshot = GeofenceSnapshot()
        val events = mutableListOf<LocationSafetyEvent>()

        listOf(1L, 61_000L, 121_000L).forEach { at ->
            val transition = machine.process(snapshot, sample(0.0, -0.02, at))
            snapshot = transition.snapshot
            events += transition.events
        }

        assertEquals(GeofenceState.AWAY, snapshot.state)
        assertEquals(null, snapshot.currentZoneId)
        assertEquals(null, snapshot.awaySessionId)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `return candidate is cancelled when user moves away again`() {
        var snapshot = calibratedInside()
        listOf(200_000L, 260_000L, 320_000L).forEach { at ->
            snapshot = machine.process(snapshot, sample(0.0, -0.01, at)).snapshot
        }
        assertEquals(GeofenceState.AWAY, snapshot.state)

        snapshot = machine.process(snapshot, sample(0.0, 0.0, 400_000L)).snapshot
        assertEquals(GeofenceState.RETURN_PENDING, snapshot.state)

        val reversed = machine.process(snapshot, sample(0.0, -0.01, 460_000L))
        assertEquals(GeofenceState.AWAY, reversed.snapshot.state)
        assertEquals(null, reversed.snapshot.candidateZoneId)
        assertTrue(reversed.events.isEmpty())
    }

    @Test
    fun `stable home to work switch emits one work arrival`() {
        var snapshot = calibratedInside()
        val events = mutableListOf<LocationSafetyEvent>()

        listOf(200_000L, 260_000L, 320_000L).forEach { at ->
            val transition = machine.process(snapshot, sample(0.0, 0.01, at))
            snapshot = transition.snapshot
            events += transition.events
        }

        assertEquals(GeofenceState.INSIDE, snapshot.state)
        assertEquals("work", snapshot.currentZoneId)
        assertEquals(1, events.size)
        assertEquals(LocationSafetyEventType.ARRIVED, events.single().type)
        assertEquals("work", events.single().zoneId)
    }

    @Test
    fun `custom named zone keeps its id and label in departure and arrival events`() {
        val customZone = SafetyZone("custom", "健身房", 0.0, 0.0, 500)
        val customMachine = GeofenceStateMachine(
            config.copy(zones = listOf(customZone))
        )
        var snapshot = GeofenceSnapshot()
        listOf(1L, 61_000L, 121_000L).forEach { at ->
            snapshot = customMachine.process(snapshot, sample(0.0, 0.0, at)).snapshot
        }

        val departureEvents = mutableListOf<LocationSafetyEvent>()
        listOf(200_000L, 260_000L, 320_000L).forEach { at ->
            val transition = customMachine.process(snapshot, sample(0.0, -0.01, at))
            snapshot = transition.snapshot
            departureEvents += transition.events
        }
        assertEquals(LocationSafetyEventType.DEPARTED, departureEvents.single().type)
        assertEquals("custom", departureEvents.single().zoneId)
        assertEquals("健身房", departureEvents.single().zoneLabel)

        val arrivalEvents = mutableListOf<LocationSafetyEvent>()
        listOf(400_000L, 460_000L, 520_000L).forEach { at ->
            val transition = customMachine.process(snapshot, sample(0.0, 0.0, at))
            snapshot = transition.snapshot
            arrivalEvents += transition.events
        }
        assertEquals(LocationSafetyEventType.ARRIVED, arrivalEvents.single().type)
        assertEquals("custom", arrivalEvents.single().zoneId)
        assertEquals("健身房", arrivalEvents.single().zoneLabel)
    }

    @Test
    fun `out of order sample is rejected without changing state`() {
        val snapshot = calibratedInside()
        val transition = machine.process(snapshot, sample(0.0, -0.02, 120_000L))

        assertFalse(transition.accepted)
        assertEquals(snapshot, transition.snapshot)
        assertTrue(transition.events.isEmpty())
    }

    @Test
    fun `stationary samples can confirm production duration exit and return`() {
        val productionMachine = GeofenceStateMachine(
            config.copy(stableDurationMs = 180_000L, minimumSampleSpacingMs = 45_000L)
        )
        var snapshot = GeofenceSnapshot()
        listOf(1L, 90_001L, 180_001L).forEach { at ->
            snapshot = productionMachine.process(snapshot, sample(0.0, 0.0, at)).snapshot
        }
        assertEquals(GeofenceState.INSIDE, snapshot.state)

        val departureEvents = mutableListOf<LocationSafetyEvent>()
        listOf(270_001L, 360_001L, 450_001L).forEach { at ->
            val transition = productionMachine.process(snapshot, sample(0.0, -0.01, at))
            snapshot = transition.snapshot
            departureEvents += transition.events
        }
        assertEquals(GeofenceState.AWAY, snapshot.state)
        assertEquals(listOf(LocationSafetyEventType.DEPARTED), departureEvents.map { it.type })

        val arrivalEvents = mutableListOf<LocationSafetyEvent>()
        listOf(540_001L, 630_001L, 720_001L).forEach { at ->
            val transition = productionMachine.process(snapshot, sample(0.0, 0.0, at))
            snapshot = transition.snapshot
            arrivalEvents += transition.events
        }
        assertEquals(GeofenceState.INSIDE, snapshot.state)
        assertEquals(listOf(LocationSafetyEventType.ARRIVED), arrivalEvents.map { it.type })
    }

    private fun calibratedInside(): GeofenceSnapshot {
        var snapshot = GeofenceSnapshot()
        listOf(1L, 61_000L, 121_000L).forEach { at ->
            snapshot = machine.process(snapshot, sample(0.0, 0.0, at)).snapshot
        }
        return snapshot
    }

    private fun sample(lat: Double, lon: Double, at: Long) =
        LocationSample(lat, lon, 10f, at)
}
