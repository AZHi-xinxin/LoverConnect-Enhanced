package com.lover.connect

import java.util.UUID

/**
 * Pure, deterministic safety-geofence state machine.
 *
 * It deliberately ignores inaccurate, duplicate and out-of-order samples.
 * Losing GPS is never interpreted as leaving a zone.
 */
class GeofenceStateMachine(private val config: LocationSafetyConfig) {

    private val awayCandidate = "__away__"

    fun process(snapshot: GeofenceSnapshot, sample: LocationSample): GeofenceTransition {
        if (!sample.isValid(config.validAccuracyMeters)) {
            return GeofenceTransition(snapshot = snapshot, accepted = false)
        }
        if (snapshot.lastAcceptedSampleAt > 0L &&
            sample.observedAt - snapshot.lastAcceptedSampleAt < config.minimumSampleSpacingMs) {
            return GeofenceTransition(snapshot = snapshot, accepted = false)
        }

        val accepted = snapshot.copy(lastAcceptedSampleAt = sample.observedAt)
        return when (accepted.state) {
            GeofenceState.UNKNOWN -> calibrate(accepted, sample)
            GeofenceState.INSIDE -> observeInside(accepted, sample)
            GeofenceState.EXIT_PENDING -> confirmExit(accepted, sample)
            GeofenceState.AWAY -> observeAway(accepted, sample)
            GeofenceState.RETURN_PENDING -> confirmReturn(accepted, sample)
        }
    }

    private fun calibrate(snapshot: GeofenceSnapshot, sample: LocationSample): GeofenceTransition {
        val nearest = nearestWithin(sample) { it.enterRadiusMeters }
        val candidate = nearest?.first?.id ?: awayCandidate
        val advanced = advanceCandidate(snapshot, candidate, sample.observedAt)
        if (!isStable(advanced, sample.observedAt)) return GeofenceTransition(advanced)

        return if (candidate == awayCandidate) {
            GeofenceTransition(clearCandidate(advanced).copy(state = GeofenceState.AWAY))
        } else {
            GeofenceTransition(
                clearCandidate(advanced).copy(
                    state = GeofenceState.INSIDE,
                    currentZoneId = candidate
                )
            )
        }
    }

    private fun observeInside(snapshot: GeofenceSnapshot, sample: LocationSample): GeofenceTransition {
        val current = zone(snapshot.currentZoneId)
            ?: return GeofenceTransition(clearCandidate(snapshot).copy(state = GeofenceState.UNKNOWN))
        val currentDistance = GeoMath.distanceMeters(sample, current)
        if (currentDistance <= current.exitRadiusMeters) {
            return GeofenceTransition(clearCandidate(snapshot))
        }

        // When zones overlap, the closest valid zone wins. A zone switch is
        // still subjected to the same stable-return confirmation.
        val nearest = nearestWithin(sample) { it.enterRadiusMeters }
        if (nearest != null && nearest.first.id != current.id) {
            return GeofenceTransition(
                snapshot.copy(
                    state = GeofenceState.RETURN_PENDING,
                    candidateZoneId = nearest.first.id,
                    candidateSince = sample.observedAt,
                    candidateSamples = 1
                )
            )
        }

        return GeofenceTransition(
            snapshot.copy(
                state = GeofenceState.EXIT_PENDING,
                candidateZoneId = awayCandidate,
                candidateSince = sample.observedAt,
                candidateSamples = 1
            )
        )
    }

    private fun confirmExit(snapshot: GeofenceSnapshot, sample: LocationSample): GeofenceTransition {
        val current = zone(snapshot.currentZoneId)
            ?: return GeofenceTransition(clearCandidate(snapshot).copy(state = GeofenceState.UNKNOWN))
        if (GeoMath.distanceMeters(sample, current) <= current.exitRadiusMeters) {
            return GeofenceTransition(clearCandidate(snapshot).copy(state = GeofenceState.INSIDE))
        }

        val nearest = nearestWithin(sample) { it.enterRadiusMeters }
        if (nearest != null && nearest.first.id != current.id) {
            return confirmReturn(
                snapshot.copy(
                    state = GeofenceState.RETURN_PENDING,
                    candidateZoneId = nearest.first.id,
                    candidateSince = sample.observedAt,
                    candidateSamples = 1
                ),
                sample,
                alreadyCounted = true
            )
        }

        val advanced = advanceCandidate(snapshot, awayCandidate, sample.observedAt)
        if (!isStable(advanced, sample.observedAt)) return GeofenceTransition(advanced)

        val sessionId = UUID.randomUUID().toString()
        val event = LocationSafetyEvent(
            type = LocationSafetyEventType.DEPARTED,
            awaySessionId = sessionId,
            zoneId = current.id,
            zoneLabel = current.label,
            occurredAt = sample.observedAt,
            distanceBucket = GeoMath.distanceBucket(GeoMath.distanceMeters(sample, current))
        )
        return GeofenceTransition(
            snapshot = clearCandidate(advanced).copy(
                state = GeofenceState.AWAY,
                currentZoneId = null,
                awaySessionId = sessionId,
                departedAt = sample.observedAt,
                originZoneId = current.id,
                distanceReminderSent = false
            ),
            events = listOf(event)
        )
    }

    private fun observeAway(snapshot: GeofenceSnapshot, sample: LocationSample): GeofenceTransition {
        val nearest = nearestWithin(sample) { it.enterRadiusMeters }
        if (nearest != null) {
            return GeofenceTransition(
                snapshot.copy(
                    state = GeofenceState.RETURN_PENDING,
                    candidateZoneId = nearest.first.id,
                    candidateSince = sample.observedAt,
                    candidateSamples = 1
                )
            )
        }

        val session = snapshot.awaySessionId
        val origin = zone(snapshot.originZoneId)
        if (session != null && origin != null && !snapshot.distanceReminderSent &&
            sample.observedAt - snapshot.departedAt >= config.secondReminderDelayMs) {
            val distance = GeoMath.distanceMeters(sample, origin)
            if (distance >= config.secondReminderMeters) {
                val event = LocationSafetyEvent(
                    type = LocationSafetyEventType.DISTANCE_REMINDER,
                    awaySessionId = session,
                    zoneId = origin.id,
                    zoneLabel = origin.label,
                    occurredAt = sample.observedAt,
                    distanceBucket = GeoMath.distanceBucket(distance)
                )
                return GeofenceTransition(
                    snapshot.copy(distanceReminderSent = true),
                    events = listOf(event)
                )
            }
        }
        return GeofenceTransition(clearCandidate(snapshot))
    }

    private fun confirmReturn(
        snapshot: GeofenceSnapshot,
        sample: LocationSample,
        alreadyCounted: Boolean = false
    ): GeofenceTransition {
        val nearest = nearestWithin(sample) { it.enterRadiusMeters }
        val directSwitchOrigin = snapshot.currentZoneId
            ?.takeIf { snapshot.awaySessionId == null }

        // A direct zone-to-zone candidate starts while the original zone is
        // still authoritative. Returning to that original zone cancels the
        // candidate silently; it must not create a synthetic "arrival home".
        if (directSwitchOrigin != null && nearest?.first?.id == directSwitchOrigin) {
            return GeofenceTransition(
                clearCandidate(snapshot).copy(state = GeofenceState.INSIDE)
            )
        }

        if (nearest == null) {
            // If a direct-switch candidate moves outside every zone, resume
            // the ordinary departure confirmation from sample one. Jumping
            // straight to AWAY would lose the origin and never emit a valid
            // departure/session event.
            if (directSwitchOrigin != null) {
                return GeofenceTransition(
                    snapshot.copy(
                        state = GeofenceState.EXIT_PENDING,
                        candidateZoneId = awayCandidate,
                        candidateSince = sample.observedAt,
                        candidateSamples = 1,
                    )
                )
            }
            return GeofenceTransition(clearCandidate(snapshot).copy(state = GeofenceState.AWAY))
        }

        val advanced = if (alreadyCounted) snapshot else advanceCandidate(
            snapshot,
            nearest.first.id,
            sample.observedAt
        )
        if (!isStable(advanced, sample.observedAt)) return GeofenceTransition(advanced)

        // Direct zone-to-zone switches have no preceding AWAY session. The
        // server contract still requires a bare UUID; the former "arrival-"
        // prefix made these valid arrivals fail HTTP validation and disappear.
        val sessionId = advanced.awaySessionId ?: UUID.randomUUID().toString()
        val event = LocationSafetyEvent(
            type = LocationSafetyEventType.ARRIVED,
            awaySessionId = sessionId,
            zoneId = nearest.first.id,
            zoneLabel = nearest.first.label,
            occurredAt = sample.observedAt,
            distanceBucket = GeoMath.distanceBucket(nearest.second)
        )
        return GeofenceTransition(
            snapshot = clearCandidate(advanced).copy(
                state = GeofenceState.INSIDE,
                currentZoneId = nearest.first.id,
                awaySessionId = null,
                departedAt = 0L,
                originZoneId = null,
                distanceReminderSent = false
            ),
            events = listOf(event)
        )
    }

    private fun advanceCandidate(
        snapshot: GeofenceSnapshot,
        candidate: String,
        observedAt: Long
    ): GeofenceSnapshot = if (snapshot.candidateZoneId == candidate) {
        snapshot.copy(candidateSamples = snapshot.candidateSamples + 1)
    } else {
        snapshot.copy(
            candidateZoneId = candidate,
            candidateSince = observedAt,
            candidateSamples = 1
        )
    }

    private fun isStable(snapshot: GeofenceSnapshot, observedAt: Long): Boolean =
        snapshot.candidateSamples >= 3 &&
            observedAt - snapshot.candidateSince >= config.stableDurationMs

    private fun clearCandidate(snapshot: GeofenceSnapshot): GeofenceSnapshot = snapshot.copy(
        candidateZoneId = null,
        candidateSince = 0L,
        candidateSamples = 0
    )

    private fun zone(id: String?): SafetyZone? = config.zones.firstOrNull { it.id == id }

    private fun nearestWithin(
        sample: LocationSample,
        threshold: (SafetyZone) -> Double
    ): Pair<SafetyZone, Double>? = config.zones
        .asSequence()
        .map { it to GeoMath.distanceMeters(sample, it) }
        .filter { (zone, distance) -> distance <= threshold(zone) }
        .minByOrNull { it.second }
}
