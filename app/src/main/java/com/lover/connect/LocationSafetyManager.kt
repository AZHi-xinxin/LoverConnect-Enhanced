package com.lover.connect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class LocationSafetyStatus(
    val trackingEnabled: Boolean,
    val paused: Boolean,
    val preciseLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val configuredZoneIds: Set<String>,
    val state: GeofenceState,
    val currentZoneId: String?,
    val pendingEvents: Int,
    val reportedOnceArmed: Boolean,
    val currentTripAcknowledged: Boolean,
    val configReadable: Boolean,
    val diagnostics: LocationSafetyDiagnostics
)

enum class ReportButtonResult {
    CURRENT_TRIP_QUEUED,
    CURRENT_TRIP_ALREADY_QUEUED,
    NEXT_DEPARTURE_ARMED,
    NEXT_DEPARTURE_CANCELLED
}

object LocationSafetyManager {
    fun start(context: Context): Result<Unit> = runCatching {
        val app = context.applicationContext
        check(hasPreciseLocation(app)) { "Precise location permission is required" }
        val config = SecureLocationConfigStore(app).load()
        check(config.zones.isNotEmpty()) { "Set at least one safety zone first" }
        LocationSafetyRuntimeStore(app).setTracking(enabled = true, paused = false)
        LocationSafetyUploader.trigger(app)
        ContextCompat.startForegroundService(
            app,
            Intent(app, LocationTrackingService::class.java)
                .setAction(LocationTrackingService.ACTION_START)
        )
    }

    fun pause(context: Context) {
        val app = context.applicationContext
        LocationSafetyRuntimeStore(app).setPaused(true)
        ContextCompat.startForegroundService(
            app,
            Intent(app, LocationTrackingService::class.java)
                .setAction(LocationTrackingService.ACTION_PAUSE)
        )
    }

    fun resume(context: Context): Result<Unit> = start(context)

    fun stop(context: Context) {
        val app = context.applicationContext
        LocationSafetyRuntimeStore(app).setTracking(enabled = false, paused = false)
        app.stopService(Intent(app, LocationTrackingService::class.java))
    }

    fun clearAll(context: Context) {
        val app = context.applicationContext
        stop(app)
        SecureLocationConfigStore(app).clear()
        LocationSafetyRuntimeStore(app).clear()
        LocationSafetyEventStore(app).use { it.clearAll() }
        LocationSafetyUploader.cancelRetry(app)
    }

    fun markReportedOnce(context: Context) {
        LocationSafetyRuntimeStore(context).markReportedOnce()
    }

    fun clearReportedOnce(context: Context) {
        LocationSafetyRuntimeStore(context).clearReportedOnce()
    }

    fun handleReportButton(context: Context): ReportButtonResult {
        val app = context.applicationContext
        val runtime = LocationSafetyRuntimeStore(app)
        val snapshot = runtime.loadSnapshot()
        val sessionId = snapshot.awaySessionId
        val activeTrip = snapshot.state == GeofenceState.AWAY ||
            snapshot.state == GeofenceState.RETURN_PENDING
        if (activeTrip && sessionId != null) {
            if (runtime.isSessionAcknowledged(sessionId)) {
                return ReportButtonResult.CURRENT_TRIP_ALREADY_QUEUED
            }
            val config = runCatching { SecureLocationConfigStore(app).load() }.getOrNull()
            val zoneId = snapshot.originZoneId ?: snapshot.currentZoneId ?: "unknown"
            val zoneLabel = config?.zones?.firstOrNull { it.id == zoneId }?.label ?: "安全区域"
            val queued = LocationSafetyEventStore(app).use { store ->
                store.enqueue(
                    LocationSafetyEvent(
                        type = LocationSafetyEventType.REPORT_ACKNOWLEDGED,
                        awaySessionId = sessionId,
                        zoneId = zoneId,
                        zoneLabel = zoneLabel,
                        occurredAt = System.currentTimeMillis(),
                        reportedOverride = true
                    )
                )
            }
            if (queued) {
                runtime.markSessionAcknowledged(sessionId)
                LocationSafetyUploader.trigger(app)
                return ReportButtonResult.CURRENT_TRIP_QUEUED
            }
            return ReportButtonResult.CURRENT_TRIP_ALREADY_QUEUED
        }

        return if (runtime.hasReportedOnce()) {
            runtime.clearReportedOnce()
            ReportButtonResult.NEXT_DEPARTURE_CANCELLED
        } else {
            runtime.markReportedOnce()
            ReportButtonResult.NEXT_DEPARTURE_ARMED
        }
    }

    fun status(context: Context): LocationSafetyStatus {
        val app = context.applicationContext
        val runtime = LocationSafetyRuntimeStore(app)
        val snapshot = runtime.loadSnapshot()
        val config = runCatching { SecureLocationConfigStore(app).load() }
        val pending = LocationSafetyEventStore(app).use { it.pendingCount() }
        return LocationSafetyStatus(
            trackingEnabled = runtime.isTrackingEnabled(),
            paused = runtime.isPaused(),
            preciseLocationGranted = hasPreciseLocation(app),
            backgroundLocationGranted = hasBackgroundLocation(app),
            configuredZoneIds = config.getOrNull()?.zones?.map { it.id }?.toSet().orEmpty(),
            state = snapshot.state,
            currentZoneId = snapshot.currentZoneId,
            pendingEvents = pending,
            reportedOnceArmed = runtime.hasReportedOnce(),
            currentTripAcknowledged = runtime.isSessionAcknowledged(snapshot.awaySessionId),
            configReadable = config.isSuccess,
            diagnostics = runtime.loadDiagnostics()
        )
    }

    fun restoreAfterBoot(context: Context) {
        val app = context.applicationContext
        LocationSafetyUploader.trigger(app)
        val runtime = LocationSafetyRuntimeStore(app)
        if (!runtime.isTrackingEnabled() || runtime.isPaused()) return
        // Android 14+ forbids creating a location FGS from the background
        // without all-the-time location. Do not attempt to bypass it.
        if (!hasPreciseLocation(app) || !hasBackgroundLocation(app)) return
        runCatching {
            val hasZones = SecureLocationConfigStore(app).load().zones.isNotEmpty()
            if (hasZones) {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, LocationTrackingService::class.java)
                        .setAction(LocationTrackingService.ACTION_START)
                )
            }
        }
    }

    fun hasPreciseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
