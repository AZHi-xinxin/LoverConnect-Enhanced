package com.lover.connect

enum class LocationUploadDisposition {
    DELIVERED,
    RETRY,
    REJECTED
}

object LocationSafetyUploadPolicy {
    fun disposition(httpCode: Int): LocationUploadDisposition = when {
        httpCode in 200..299 -> LocationUploadDisposition.DELIVERED
        httpCode in setOf(400, 413, 415, 422) -> LocationUploadDisposition.REJECTED
        else -> LocationUploadDisposition.RETRY
    }

    fun retryDelayMs(attemptNumber: Int): Long = when (attemptNumber.coerceAtLeast(1)) {
        1 -> 60_000L
        2 -> 120_000L
        3 -> 300_000L
        else -> 900_000L
    }

    fun wireType(type: LocationSafetyEventType): String = when (type) {
        LocationSafetyEventType.DEPARTED -> "zone_exit_confirmed"
        LocationSafetyEventType.DISTANCE_REMINDER -> "distance_tier_crossed"
        LocationSafetyEventType.ARRIVED -> "zone_enter_confirmed"
        LocationSafetyEventType.LOCATION_DEGRADED -> "location_degraded"
        LocationSafetyEventType.TRACKING_PAUSED -> "tracking_paused"
        LocationSafetyEventType.OFFLINE_TRIP_SUMMARY -> "offline_trip_summary"
        LocationSafetyEventType.REPORT_ACKNOWLEDGED -> "report_acknowledged"
    }
}
