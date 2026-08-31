package com.lover.connect

import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object LocationSafetyRules {
    const val HOME_ZONE_ID = "home"
    const val WORK_ZONE_ID = "work"
    const val CUSTOM_ZONE_ID = "custom"
    const val MAX_ZONE_LABEL_LENGTH = 24

    fun normalizeZoneLabel(input: CharSequence?): String = input?.toString().orEmpty()
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun isValidZoneLabel(input: CharSequence?): Boolean {
        val normalized = normalizeZoneLabel(input)
        return normalized.isNotEmpty() && normalized.length <= MAX_ZONE_LABEL_LENGTH
    }

    /**
     * A zone edit is user configuration, not physical movement. Reconcile only
     * the state that refers to the edited zone so new coordinates cannot
     * manufacture a departure, while a real trip already in progress survives.
     */
    fun reconcileSnapshotAfterZoneChange(
        snapshot: GeofenceSnapshot,
        changedZoneId: String,
        configuredZoneIds: Set<String>,
    ): GeofenceSnapshot {
        if (snapshot.currentZoneId == changedZoneId) return GeofenceSnapshot()

        if (snapshot.originZoneId == changedZoneId) {
            return snapshot.copy(
                state = GeofenceState.AWAY,
                currentZoneId = null,
                candidateZoneId = null,
                candidateSince = 0L,
                candidateSamples = 0,
                lastAcceptedSampleAt = 0L,
                originZoneId = null,
                // The former origin coordinates are gone. Never calculate a
                // distance reminder against the replacement coordinates.
                distanceReminderSent = true,
            )
        }

        if (snapshot.candidateZoneId == changedZoneId) {
            return if (snapshot.awaySessionId != null && snapshot.currentZoneId == null) {
                snapshot.copy(
                    state = GeofenceState.AWAY,
                    candidateZoneId = null,
                    candidateSince = 0L,
                    candidateSamples = 0,
                    lastAcceptedSampleAt = 0L,
                    originZoneId = snapshot.originZoneId?.takeIf { it in configuredZoneIds },
                )
            } else {
                GeofenceSnapshot()
            }
        }

        return snapshot
    }
}

enum class GeofenceState {
    UNKNOWN,
    INSIDE,
    EXIT_PENDING,
    AWAY,
    RETURN_PENDING
}

enum class LocationSafetyEventType {
    DEPARTED,
    DISTANCE_REMINDER,
    ARRIVED,
    LOCATION_DEGRADED,
    TRACKING_PAUSED,
    OFFLINE_TRIP_SUMMARY,
    REPORT_ACKNOWLEDGED
}

data class SafetyZone(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 500
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]{1,32}"))) { "Invalid zone id" }
        require(label.isNotBlank() && label.length <= LocationSafetyRules.MAX_ZONE_LABEL_LENGTH) {
            "Invalid zone label"
        }
        require(latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude" }
        require(radiusMeters in 200..2_000) { "radiusMeters must be 200..2000" }
    }

    val enterRadiusMeters: Double get() = max(100, radiusMeters - 150).toDouble()
    val exitRadiusMeters: Double get() = (radiusMeters + 100).toDouble()
}

data class LocationSafetyConfig(
    val zones: List<SafetyZone> = emptyList(),
    val secondReminderMeters: Int = 5_000,
    val stableDurationMs: Long = 180_000L,
    val validAccuracyMeters: Float = 100f,
    val minimumSampleSpacingMs: Long = 45_000L,
    val secondReminderDelayMs: Long = 900_000L
) {
    init {
        require(zones.map { it.id }.distinct().size == zones.size) { "Zone ids must be unique" }
        require(zones.size <= 4) { "At most four zones are supported" }
        require(secondReminderMeters in 2_000..10_000) { "Second reminder must be 2..10 km" }
        require(stableDurationMs in 60_000L..300_000L) { "Stable duration must be 1..5 minutes" }
        require(validAccuracyMeters in 20f..200f) { "Invalid accuracy threshold" }
        require(minimumSampleSpacingMs in 1_000L..180_000L) { "Invalid sample spacing" }
        require(secondReminderDelayMs >= 900_000L) { "Second reminder delay must be at least 15 minutes" }
    }
}

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val observedAt: Long
) {
    fun isValid(maxAccuracyMeters: Float): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() &&
            accuracyMeters > 0f &&
            accuracyMeters <= maxAccuracyMeters &&
            observedAt > 0L
}

data class GeofenceSnapshot(
    val state: GeofenceState = GeofenceState.UNKNOWN,
    val currentZoneId: String? = null,
    val candidateZoneId: String? = null,
    val candidateSince: Long = 0L,
    val candidateSamples: Int = 0,
    val lastAcceptedSampleAt: Long = 0L,
    val awaySessionId: String? = null,
    val departedAt: Long = 0L,
    val originZoneId: String? = null,
    val distanceReminderSent: Boolean = false
)

data class LocationSafetyEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val type: LocationSafetyEventType,
    val awaySessionId: String,
    val zoneId: String,
    val zoneLabel: String,
    val occurredAt: Long,
    val distanceBucket: String? = null,
    val reportedOverride: Boolean = false
)

data class GeofenceTransition(
    val snapshot: GeofenceSnapshot,
    val events: List<LocationSafetyEvent> = emptyList(),
    val accepted: Boolean = true
)

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double
    ): Double {
        val lat1 = Math.toRadians(latitudeA)
        val lat2 = Math.toRadians(latitudeB)
        val deltaLat = Math.toRadians(latitudeB - latitudeA)
        val deltaLon = Math.toRadians(longitudeB - longitudeA)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    fun distanceMeters(sample: LocationSample, zone: SafetyZone): Double =
        distanceMeters(sample.latitude, sample.longitude, zone.latitude, zone.longitude)

    fun distanceBucket(distanceMeters: Double): String = when {
        distanceMeters < 1_000 -> "under_1km"
        distanceMeters < 5_000 -> "1_to_5km"
        distanceMeters < 10_000 -> "5_to_10km"
        else -> "over_10km"
    }
}
