package com.lover.connect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Ordered, durable sender for coordinate-free location events. */
object LocationSafetyUploader {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lc-location-uploader").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    fun trigger(context: Context, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            onComplete?.invoke()
            return
        }
        executor.execute {
            var retryDelay: Long? = null
            try {
                retryDelay = drain(app)
            } finally {
                running.set(false)
                if (retryDelay != null) {
                    scheduleRetry(app, retryDelay!!)
                } else if (hasUploadablePendingEvents(app)) {
                    // Covers an event queued just after the final drain query.
                    scheduleRetry(app, 5_000L)
                } else {
                    cancelRetry(app)
                }
                onComplete?.invoke()
            }
        }
    }

    private fun drain(context: Context): Long? {
        val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sentinel_enabled", false)) return null
        val token = prefs.getString("sentinel_token", "")?.trim().orEmpty()
        val configuredUrl = prefs.getString("sentinel_url", "")?.trim().orEmpty()
        val endpoint = SentinelEndpointPolicy.locationEventsUrl(configuredUrl)
        if (token.length < 16 || endpoint == null) return null

        LocationSafetyEventStore(context).use { store ->
            store.prune()
            while (true) {
                val batch = store.pending(MAX_BATCH)
                if (batch.isEmpty()) return null
                for (queued in batch) {
                    store.markAttempt(queued.event.eventId)
                    val code = post(context, endpoint, token, queued.event)
                    when (if (code == null) LocationUploadDisposition.RETRY
                    else LocationSafetyUploadPolicy.disposition(code)) {
                        LocationUploadDisposition.DELIVERED ->
                            store.markDelivered(queued.event.eventId)

                        LocationUploadDisposition.REJECTED ->
                            store.markRejected(queued.event.eventId)

                        LocationUploadDisposition.RETRY ->
                            return LocationSafetyUploadPolicy.retryDelayMs(queued.attempts + 1)
                    }
                }
            }
        }
    }

    private fun post(
        context: Context,
        endpoint: String,
        token: String,
        event: LocationSafetyEvent
    ): Int? {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("event_id", event.eventId)
                put("type", LocationSafetyUploadPolicy.wireType(event.type))
                put("away_session_id", event.awaySessionId)
                put("zone_id", event.zoneId)
                put("zone_label", event.zoneLabel.take(24))
                put("occurred_at", event.occurredAt)
                put("distance_bucket", event.distanceBucket ?: JSONObject.NULL)
                put("reported_override", event.reportedOverride)
                put("app_version", installedVersionName(context))
            }.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            runCatching {
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                drainResponse(stream)
            }
            code
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun hasUploadablePendingEvents(context: Context): Boolean {
        val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sentinel_enabled", false)) return false
        val endpoint = SentinelEndpointPolicy.locationEventsUrl(
            prefs.getString("sentinel_url", "").orEmpty()
        )
        val token = prefs.getString("sentinel_token", "").orEmpty()
        if (endpoint == null || token.length < 16) return false
        return LocationSafetyEventStore(context).use { it.pendingCount() > 0 }
    }

    private fun drainResponse(stream: java.io.InputStream?) {
        if (stream == null) return
        stream.use { input ->
            val buffer = ByteArray(4_096)
            var remaining = MAX_RESPONSE_BYTES
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                remaining -= count
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun scheduleRetry(context: Context, delayMs: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(5_000L),
            retryIntent(context)
        )
    }

    fun cancelRetry(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(retryIntent(context.applicationContext))
    }

    private fun retryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        RETRY_REQUEST_CODE,
        Intent(context, LocationEventUploadReceiver::class.java)
            .setAction(ACTION_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private const val MAX_BATCH = 50
    private const val MAX_RESPONSE_BYTES = 32 * 1_024
    private const val RETRY_REQUEST_CODE = 4204
    private const val ACTION_RETRY = "com.lover.connect.location.UPLOAD_RETRY"
}

class LocationEventUploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        LocationSafetyUploader.trigger(context) { pendingResult.finish() }
    }
}
