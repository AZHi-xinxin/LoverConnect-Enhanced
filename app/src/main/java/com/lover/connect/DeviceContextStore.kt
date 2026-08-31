package com.lover.connect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DeviceContextSettings {
    const val CONFIG_PREFS = "lc_device_context_config"
    const val RUNTIME_PREFS = "lc_device_context_runtime"
    const val KEY_ENABLED = "enabled"
    const val KEY_NOTIFICATION_SUMMARIES = "notification_summaries"
    const val KEY_NOTIFICATION_TEXT = "notification_text"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun notificationSummariesEnabled(context: Context): Boolean =
        isEnabled(context) && context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_SUMMARIES, false)

    fun notificationTextEnabled(context: Context): Boolean =
        notificationSummariesEnabled(context) &&
            context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_TEXT, false)
}

class DeviceContextStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences(DeviceContextSettings.RUNTIME_PREFS, Context.MODE_PRIVATE)

    fun recordObservation(
        kind: String,
        value: String,
        observedAtMs: Long = System.currentTimeMillis(),
        source: String = "device_sensor",
    ) = synchronized(LOCK) {
        val previous = prefs.getString("last_$kind", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val changed = previous?.optString("value") != value
        val observation = JSONObject().apply {
            put("kind", kind.take(40))
            put("value", value.take(120))
            put("observed_at_ms", observedAtMs)
            put("source", source.take(40))
        }
        val editor = prefs.edit().putString("last_$kind", observation.toString())
        if (changed) {
            val events = readArray(KEY_EVENTS)
            events.put(JSONObject(observation.toString()).apply {
                put("transition_from", previous?.optString("value") ?: JSONObject.NULL)
            })
            editor.putString(KEY_EVENTS, prune(events, observedAtMs, EVENT_TTL_MS, MAX_EVENTS).toString())
        }
        editor.apply()
    }

    fun recordNotification(
        notificationKey: String,
        appPackage: String,
        appLabel: String,
        category: String?,
        title: String,
        text: String,
        ongoing: Boolean,
        postedAtMs: Long,
        observedAtMs: Long = System.currentTimeMillis(),
    ) = synchronized(LOCK) {
        // A listener task may have been queued before the user disabled
        // capture. Re-check consent while holding the same lock used by
        // clear/strip so queued work cannot restore rejected data.
        if (!DeviceContextSettings.notificationSummariesEnabled(appContext)) return@synchronized
        val includeText = DeviceContextSettings.notificationTextEnabled(appContext)
        val item = JSONObject().apply {
            put("notification_key", notificationKey.take(240))
            put("app_package", appPackage.take(160))
            put("app_label", DeviceContextLogic.sanitizeUntrustedText(appLabel, 80))
            put(
                "category",
                category?.let { DeviceContextLogic.sanitizeUntrustedText(it, 60) }
                    ?: JSONObject.NULL,
            )
            put(
                "title",
                if (includeText) DeviceContextLogic.redactNotificationText(title, 120) else "",
            )
            put(
                "text",
                if (includeText) DeviceContextLogic.redactNotificationText(text, 160) else "",
            )
            put("ongoing", ongoing)
            put("posted_at_ms", postedAtMs)
            put("observed_at_ms", observedAtMs)
            put("source", "notification_listener")
            put("provenance", "external_app_notification")
            put("content_trust", "untrusted_external_content")
            put("instruction_authority", "none")
        }
        NOTIFICATION_RING.removeAll { it.optString("notification_key") == notificationKey }
        NOTIFICATION_RING += item
        pruneNotificationRing(observedAtMs)
    }

    fun removeNotification(notificationKey: String) = synchronized(LOCK) {
        NOTIFICATION_RING.removeAll { it.optString("notification_key") == notificationKey }
    }

    fun latestObservations(nowMs: Long = System.currentTimeMillis()): JSONObject = synchronized(LOCK) {
        val result = JSONObject()
        OBSERVATION_KINDS.forEach { kind ->
            val raw = prefs.getString("last_$kind", null) ?: return@forEach
            runCatching { JSONObject(raw) }.getOrNull()?.let { item ->
                val observedAt = item.optLong("observed_at_ms", 0L)
                item.put("age_seconds", ((nowMs - observedAt).coerceAtLeast(0L) / 1_000L))
                item.put("freshness", DeviceContextLogic.freshness(observedAt, nowMs))
                result.put(kind, item)
            }
        }
        result
    }

    fun recentEvents(limit: Int, nowMs: Long = System.currentTimeMillis()): JSONArray = synchronized(LOCK) {
        val pruned = prune(readArray(KEY_EVENTS), nowMs, EVENT_TTL_MS, MAX_EVENTS)
        prefs.edit().putString(KEY_EVENTS, pruned.toString()).apply()
        takeLastReversed(pruned, limit.coerceIn(1, MAX_EVENTS), nowMs)
    }

    fun recentNotifications(limit: Int, nowMs: Long = System.currentTimeMillis()): JSONArray = synchronized(LOCK) {
        pruneNotificationRing(nowMs)
        val result = JSONArray()
        NOTIFICATION_RING.takeLast(limit.coerceIn(1, MAX_NOTIFICATIONS)).asReversed().forEach { stored ->
            val copy = JSONObject(stored.toString())
            copy.remove("notification_key")
            val observedAt = copy.optLong("observed_at_ms", 0L)
            copy.put("age_seconds", ((nowMs - observedAt).coerceAtLeast(0L) / 1_000L))
            result.put(copy)
        }
        result
    }

    fun clearEphemeral() = synchronized(LOCK) {
        prefs.edit().clear().apply()
        NOTIFICATION_RING.clear()
    }

    fun clearNotifications() = synchronized(LOCK) {
        NOTIFICATION_RING.clear()
    }

    fun stripNotificationText() = synchronized(LOCK) {
        NOTIFICATION_RING.forEach { item ->
            item.put("title", "")
            item.put("text", "")
        }
    }

    private fun readArray(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrDefault(JSONArray())

    private fun prune(source: JSONArray, nowMs: Long, ttlMs: Long, maxItems: Int): JSONArray {
        val kept = mutableListOf<JSONObject>()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val timestamp = item.optLong("observed_at_ms", 0L)
            if (timestamp > 0L && nowMs - timestamp in 0..ttlMs) kept += item
        }
        return JSONArray(kept.takeLast(maxItems))
    }

    private fun takeLastReversed(source: JSONArray, limit: Int, nowMs: Long): JSONArray {
        val result = JSONArray()
        var emitted = 0
        for (i in source.length() - 1 downTo 0) {
            if (emitted >= limit) break
            val item = source.optJSONObject(i) ?: continue
            val copy = JSONObject(item.toString())
            val observedAt = copy.optLong("observed_at_ms", 0L)
            copy.put("age_seconds", ((nowMs - observedAt).coerceAtLeast(0L) / 1_000L))
            result.put(copy)
            emitted += 1
        }
        return result
    }

    private fun pruneNotificationRing(nowMs: Long) {
        NOTIFICATION_RING.removeAll { item ->
            val observedAt = item.optLong("observed_at_ms", 0L)
            observedAt <= 0L || nowMs - observedAt !in 0..NOTIFICATION_TTL_MS
        }
        while (NOTIFICATION_RING.size > MAX_NOTIFICATIONS) NOTIFICATION_RING.removeAt(0)
    }

    companion object {
        private val LOCK = Any()
        private const val KEY_EVENTS = "events"
        private const val EVENT_TTL_MS = 24 * 60 * 60_000L
        private const val NOTIFICATION_TTL_MS = 30 * 60_000L
        private const val MAX_EVENTS = 50
        private const val MAX_NOTIFICATIONS = 20
        private val NOTIFICATION_RING = mutableListOf<JSONObject>()
        private val OBSERVATION_KINDS = listOf("pose", "motion", "light", "proximity", "screen", "power")
    }
}
