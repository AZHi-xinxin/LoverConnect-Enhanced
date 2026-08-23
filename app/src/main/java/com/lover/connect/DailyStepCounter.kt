package com.lover.connect

internal data class DailyStepState(
    val date: String = "",
    val count: Int = 0,
    val lastSensorTotal: Int = -1,
)

/**
 * Turns Android's boot-wide TYPE_STEP_COUNTER value into a restart-safe daily count.
 *
 * The last sensor total is persisted alongside the accumulated daily count. A service
 * restart therefore adds only the steps that happened while the process was away. If
 * the phone itself rebooted and the sensor total moved backwards, the existing daily
 * count is retained and the new boot value becomes the next anchor.
 */
internal object DailyStepCounter {
    fun update(previous: DailyStepState, today: String, sensorTotal: Int): DailyStepState {
        val safeTotal = sensorTotal.coerceAtLeast(0)

        if (previous.date != today) {
            return DailyStepState(date = today, count = 0, lastSensorTotal = safeTotal)
        }

        if (previous.lastSensorTotal < 0) {
            return previous.copy(lastSensorTotal = safeTotal)
        }

        val delta = if (safeTotal >= previous.lastSensorTotal) {
            safeTotal - previous.lastSensorTotal
        } else {
            // TYPE_STEP_COUNTER resets when Android reboots. Keep today's accumulated
            // steps and use the new boot-wide total as the next anchor.
            0
        }
        val updatedCount = (previous.count.toLong() + delta.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return DailyStepState(today, updatedCount, safeTotal)
    }
}
