package com.lover.connect

/**
 * Pure elapsed-time policy for recovering a foreground location subscription.
 * It deliberately knows nothing about coordinates or Android framework objects.
 */
internal class LocationCallbackWatchdog(
    private val staleAfterMs: Long = LocationSamplingPolicy.CALLBACK_STALE_AFTER_MS
) {
    private var lastRegistrationAttemptElapsedMs: Long = UNSET
    private var lastRawCallbackElapsedMs: Long = UNSET

    fun onRegistrationAttempt(nowElapsedMs: Long) {
        lastRegistrationAttemptElapsedMs = nowElapsedMs
    }

    fun onRawCallback(nowElapsedMs: Long) {
        lastRawCallbackElapsedMs = nowElapsedMs
    }

    fun shouldReregister(nowElapsedMs: Long): Boolean {
        if (lastRegistrationAttemptElapsedMs == UNSET) return true
        val baseline = maxOf(lastRegistrationAttemptElapsedMs, lastRawCallbackElapsedMs)
        if (nowElapsedMs < baseline) return false
        return nowElapsedMs - baseline >= staleAfterMs
    }

    companion object {
        private const val UNSET = -1L
    }
}

internal object LocationSamplingPolicy {
    const val INTERVAL_MS = 30_000L
    const val MIN_DISTANCE_METERS = 0f
    const val WATCHDOG_CHECK_INTERVAL_MS = 30_000L
    const val CALLBACK_STALE_AFTER_MS = 120_000L
}
