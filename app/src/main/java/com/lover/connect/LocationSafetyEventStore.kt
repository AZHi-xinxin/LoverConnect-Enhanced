package com.lover.connect

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class QueuedLocationEvent(
    val event: LocationSafetyEvent,
    val attempts: Int = 0
)

/** Durable, coordinate-free queue for the later VPS sender. */
class LocationSafetyEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE location_events (
                event_id TEXT PRIMARY KEY,
                away_session_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                zone_id TEXT NOT NULL,
                zone_label TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                distance_bucket TEXT,
                reported_override INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_location_events_pending ON location_events(status, occurred_at)")
        db.execSQL("CREATE INDEX idx_location_events_session ON location_events(away_session_id, status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueue(event: LocationSafetyEvent): Boolean = writableDatabase.insertWithOnConflict(
        TABLE,
        null,
        event.toValues(),
        SQLiteDatabase.CONFLICT_IGNORE
    ) != -1L

    fun pending(limit: Int = 50): List<QueuedLocationEvent> {
        val safeLimit = limit.coerceIn(1, 200).toString()
        return readableDatabase.query(
            TABLE,
            null,
            "status = ?",
            arrayOf(STATUS_PENDING),
            null,
            null,
            "occurred_at ASC",
            safeLimit
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        QueuedLocationEvent(
                            event = cursor.toLocationEvent(),
                            attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts"))
                        )
                    )
                }
            }
        }
    }

    fun markDelivered(eventId: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put("status", STATUS_DELIVERED) },
            "event_id = ?",
            arrayOf(eventId)
        )
    }

    fun markAttempt(eventId: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET attempts = attempts + 1 WHERE event_id = ? AND status = ?",
            arrayOf(eventId, STATUS_PENDING)
        )
    }

    fun markRejected(eventId: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put("status", STATUS_REJECTED) },
            "event_id = ? AND status = ?",
            arrayOf(eventId, STATUS_PENDING)
        )
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val deliveredBefore = now - 7L * 24 * 60 * 60 * 1_000
        val rejectedBefore = now - 30L * 24 * 60 * 60 * 1_000
        writableDatabase.delete(
            TABLE,
            "(status = ? AND created_at < ?) OR (status = ? AND created_at < ?)",
            arrayOf(
                STATUS_DELIVERED,
                deliveredBefore.toString(),
                STATUS_REJECTED,
                rejectedBefore.toString()
            )
        )
    }

    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE WHERE status = ?",
        arrayOf(STATUS_PENDING)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun pendingForSession(awaySessionId: String): List<LocationSafetyEvent> =
        readableDatabase.query(
            TABLE,
            null,
            "away_session_id = ? AND status = ?",
            arrayOf(awaySessionId, STATUS_PENDING),
            null,
            null,
            "occurred_at ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toLocationEvent())
                }
            }
        }

    /**
     * If a whole trip happened while every event was still pending, replace
     * its departure/reminder/arrival records with one stale-safe summary.
     */
    fun compactUnsentTrip(awaySessionId: String): LocationSafetyEvent? {
        val sessionEvents = pendingForSession(awaySessionId)
        val compacted = LocationEventCompactor.compactTrip(sessionEvents) ?: return null
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete(
                TABLE,
                "away_session_id = ? AND status = ?",
                arrayOf(awaySessionId, STATUS_PENDING)
            )
            db.insertOrThrow(TABLE, null, compacted.toValues())
            db.setTransactionSuccessful()
            compacted
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun LocationSafetyEvent.toValues() = ContentValues().apply {
        put("event_id", eventId)
        put("away_session_id", awaySessionId)
        put("event_type", type.name)
        put("zone_id", zoneId)
        put("zone_label", zoneLabel)
        put("occurred_at", occurredAt)
        put("distance_bucket", distanceBucket)
        put("reported_override", if (reportedOverride) 1 else 0)
        put("status", STATUS_PENDING)
        put("attempts", 0)
        put("created_at", System.currentTimeMillis())
    }

    private fun android.database.Cursor.toLocationEvent() = LocationSafetyEvent(
        eventId = getString(getColumnIndexOrThrow("event_id")),
        type = LocationSafetyEventType.valueOf(getString(getColumnIndexOrThrow("event_type"))),
        awaySessionId = getString(getColumnIndexOrThrow("away_session_id")),
        zoneId = getString(getColumnIndexOrThrow("zone_id")),
        zoneLabel = getString(getColumnIndexOrThrow("zone_label")),
        occurredAt = getLong(getColumnIndexOrThrow("occurred_at")),
        distanceBucket = getString(getColumnIndexOrThrow("distance_bucket")),
        reportedOverride = getInt(getColumnIndexOrThrow("reported_override")) == 1
    )

    companion object {
        private const val DB_NAME = "lc_location_events.db"
        private const val DB_VERSION = 1
        private const val TABLE = "location_events"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_DELIVERED = "delivered"
        private const val STATUS_REJECTED = "rejected"
    }
}

object LocationEventCompactor {
    fun compactTrip(events: List<LocationSafetyEvent>): LocationSafetyEvent? {
        if (events.isEmpty()) return null
        val ordered = events.sortedBy { it.occurredAt }
        val sessions = ordered.map { it.awaySessionId }.distinct()
        if (sessions.size != 1) return null
        val departure = ordered.firstOrNull { it.type == LocationSafetyEventType.DEPARTED } ?: return null
        val arrival = ordered.lastOrNull { it.type == LocationSafetyEventType.ARRIVED } ?: return null
        if (arrival.occurredAt < departure.occurredAt) return null
        return LocationSafetyEvent(
            type = LocationSafetyEventType.OFFLINE_TRIP_SUMMARY,
            awaySessionId = departure.awaySessionId,
            zoneId = arrival.zoneId,
            zoneLabel = arrival.zoneLabel,
            occurredAt = arrival.occurredAt,
            distanceBucket = ordered.lastOrNull {
                it.type == LocationSafetyEventType.DISTANCE_REMINDER
            }?.distanceBucket ?: departure.distanceBucket,
            reportedOverride = departure.reportedOverride || ordered.any {
                it.type == LocationSafetyEventType.REPORT_ACKNOWLEDGED
            }
        )
    }
}
