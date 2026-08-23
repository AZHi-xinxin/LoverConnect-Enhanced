package com.lover.connect

import android.content.Context
import org.json.JSONObject

data class LocationSafetyDiagnostics(
    val lastRegistrationAttemptAt: Long = 0L,
    val registeredProviders: Set<String> = emptySet(),
    val lastRegistrationError: String? = null,
    val lastRawCallbackAt: Long = 0L,
    val lastAcceptedSampleAt: Long = 0L,
    val lastRejectedReason: String? = null,
    val recoveryCount: Int = 0
)

/** Persists only coarse state-machine metadata. Raw coordinates never enter this store. */
class LocationSafetyRuntimeStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isTrackingEnabled(): Boolean = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    fun setTracking(enabled: Boolean, paused: Boolean = false) {
        prefs.edit()
            .putBoolean(KEY_TRACKING_ENABLED, enabled)
            .putBoolean(KEY_PAUSED, paused)
            .apply()
    }

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun loadSnapshot(): GeofenceSnapshot {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return GeofenceSnapshot()
        return try {
            val json = JSONObject(raw)
            GeofenceSnapshot(
                state = GeofenceState.valueOf(json.optString("state", GeofenceState.UNKNOWN.name)),
                currentZoneId = json.optNullableString("current_zone_id"),
                candidateZoneId = json.optNullableString("candidate_zone_id"),
                candidateSince = json.optLong("candidate_since", 0L),
                candidateSamples = json.optInt("candidate_samples", 0),
                lastAcceptedSampleAt = json.optLong("last_accepted_sample_at", 0L),
                awaySessionId = json.optNullableString("away_session_id"),
                departedAt = json.optLong("departed_at", 0L),
                originZoneId = json.optNullableString("origin_zone_id"),
                distanceReminderSent = json.optBoolean("distance_reminder_sent", false)
            )
        } catch (_: Exception) {
            // Corrupt coarse state is safe to forget. Encrypted zone data is untouched.
            GeofenceSnapshot()
        }
    }

    fun saveSnapshot(snapshot: GeofenceSnapshot) {
        val json = JSONObject().apply {
            put("state", snapshot.state.name)
            putNullable("current_zone_id", snapshot.currentZoneId)
            putNullable("candidate_zone_id", snapshot.candidateZoneId)
            put("candidate_since", snapshot.candidateSince)
            put("candidate_samples", snapshot.candidateSamples)
            put("last_accepted_sample_at", snapshot.lastAcceptedSampleAt)
            putNullable("away_session_id", snapshot.awaySessionId)
            put("departed_at", snapshot.departedAt)
            putNullable("origin_zone_id", snapshot.originZoneId)
            put("distance_reminder_sent", snapshot.distanceReminderSent)
        }
        prefs.edit().putString(KEY_SNAPSHOT, json.toString()).apply()
    }

    fun markReportedOnce(now: Long = System.currentTimeMillis(), validForMs: Long = 21_600_000L) {
        prefs.edit().putLong(KEY_REPORTED_ONCE_EXPIRES_AT, now + validForMs).apply()
    }

    fun hasReportedOnce(now: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong(KEY_REPORTED_ONCE_EXPIRES_AT, 0L) >= now

    fun consumeReportedOnce(now: Long = System.currentTimeMillis()): Boolean {
        val valid = hasReportedOnce(now)
        prefs.edit().remove(KEY_REPORTED_ONCE_EXPIRES_AT).apply()
        return valid
    }

    fun clearReportedOnce() {
        prefs.edit().remove(KEY_REPORTED_ONCE_EXPIRES_AT).apply()
    }

    fun markSessionAcknowledged(awaySessionId: String) {
        prefs.edit().putString(KEY_ACKNOWLEDGED_SESSION_ID, awaySessionId).apply()
    }

    fun isSessionAcknowledged(awaySessionId: String?): Boolean =
        awaySessionId != null && prefs.getString(KEY_ACKNOWLEDGED_SESSION_ID, null) == awaySessionId

    fun clearAcknowledgedSession() {
        prefs.edit().remove(KEY_ACKNOWLEDGED_SESSION_ID).apply()
    }

    fun recordRegistrationAttempt(
        at: Long,
        providers: Set<String>,
        error: String?,
        recovery: Boolean
    ) {
        val editor = prefs.edit()
            .putLong(KEY_LAST_REGISTRATION_ATTEMPT_AT, at)
            .putStringSet(KEY_REGISTERED_PROVIDERS, providers.toSet())
        if (error == null) editor.remove(KEY_LAST_REGISTRATION_ERROR)
        else editor.putString(KEY_LAST_REGISTRATION_ERROR, error)
        if (recovery) {
            editor.putInt(KEY_RECOVERY_COUNT, prefs.getInt(KEY_RECOVERY_COUNT, 0) + 1)
        }
        editor.apply()
    }

    fun recordRawCallback(at: Long) {
        prefs.edit().putLong(KEY_LAST_RAW_CALLBACK_AT, at).apply()
    }

    fun recordAcceptedSample(at: Long) {
        prefs.edit()
            .putLong(KEY_LAST_ACCEPTED_SAMPLE_AT, at)
            .remove(KEY_LAST_REJECTED_REASON)
            .apply()
    }

    fun recordRejectedSample(reason: String) {
        prefs.edit().putString(KEY_LAST_REJECTED_REASON, reason).apply()
    }

    fun loadDiagnostics(): LocationSafetyDiagnostics = LocationSafetyDiagnostics(
        lastRegistrationAttemptAt = prefs.getLong(KEY_LAST_REGISTRATION_ATTEMPT_AT, 0L),
        registeredProviders = prefs.getStringSet(KEY_REGISTERED_PROVIDERS, emptySet())
            ?.toSet()
            .orEmpty(),
        lastRegistrationError = prefs.getString(KEY_LAST_REGISTRATION_ERROR, null),
        lastRawCallbackAt = prefs.getLong(KEY_LAST_RAW_CALLBACK_AT, 0L),
        lastAcceptedSampleAt = prefs.getLong(KEY_LAST_ACCEPTED_SAMPLE_AT, 0L),
        lastRejectedReason = prefs.getString(KEY_LAST_REJECTED_REASON, null),
        recoveryCount = prefs.getInt(KEY_RECOVERY_COUNT, 0)
    )

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    companion object {
        private const val PREFS_NAME = "lc_location_runtime"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_PAUSED = "paused"
        private const val KEY_SNAPSHOT = "geofence_snapshot"
        private const val KEY_REPORTED_ONCE_EXPIRES_AT = "reported_once_expires_at"
        private const val KEY_ACKNOWLEDGED_SESSION_ID = "acknowledged_session_id"
        private const val KEY_LAST_REGISTRATION_ATTEMPT_AT = "last_registration_attempt_at"
        private const val KEY_REGISTERED_PROVIDERS = "registered_providers"
        private const val KEY_LAST_REGISTRATION_ERROR = "last_registration_error"
        private const val KEY_LAST_RAW_CALLBACK_AT = "last_raw_callback_at"
        private const val KEY_LAST_ACCEPTED_SAMPLE_AT = "last_accepted_sample_at"
        private const val KEY_LAST_REJECTED_REASON = "last_rejected_reason"
        private const val KEY_RECOVERY_COUNT = "location_recovery_count"
    }
}
