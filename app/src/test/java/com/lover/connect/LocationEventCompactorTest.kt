package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationEventCompactorTest {
    @Test
    fun `complete unsent trip becomes one arrival summary`() {
        val departure = event(LocationSafetyEventType.DEPARTED, 100L, "home", true)
        val reminder = event(LocationSafetyEventType.DISTANCE_REMINDER, 1_000L, "home")
        val arrival = event(LocationSafetyEventType.ARRIVED, 2_000L, "work")

        val summary = LocationEventCompactor.compactTrip(listOf(departure, reminder, arrival))

        assertEquals(LocationSafetyEventType.OFFLINE_TRIP_SUMMARY, summary?.type)
        assertEquals("work", summary?.zoneId)
        assertTrue(summary?.reportedOverride == true)
    }

    @Test
    fun `incomplete or mixed session is not compacted`() {
        assertNull(LocationEventCompactor.compactTrip(listOf(event(LocationSafetyEventType.DEPARTED, 1L))))
        assertNull(
            LocationEventCompactor.compactTrip(
                listOf(
                    event(LocationSafetyEventType.DEPARTED, 1L),
                    event(LocationSafetyEventType.ARRIVED, 2L).copy(awaySessionId = "other")
                )
            )
        )
    }

    @Test
    fun `acknowledgement inside offline trip survives compaction`() {
        val summary = LocationEventCompactor.compactTrip(
            listOf(
                event(LocationSafetyEventType.DEPARTED, 100L),
                event(LocationSafetyEventType.REPORT_ACKNOWLEDGED, 200L),
                event(LocationSafetyEventType.ARRIVED, 300L, "work")
            )
        )

        assertTrue(summary?.reportedOverride == true)
    }

    private fun event(
        type: LocationSafetyEventType,
        at: Long,
        zone: String = "home",
        override: Boolean = false
    ) = LocationSafetyEvent(
        eventId = "$type-$at",
        type = type,
        awaySessionId = "session-1",
        zoneId = zone,
        zoneLabel = zone,
        occurredAt = at,
        distanceBucket = "1_to_5km",
        reportedOverride = override
    )
}
