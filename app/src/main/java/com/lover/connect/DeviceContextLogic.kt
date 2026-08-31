package com.lover.connect

import kotlin.math.abs
import kotlin.math.sqrt

/** Pure device-context rules. Human state must never be inferred here. */
object DeviceContextLogic {
    fun classifyPose(x: Float, y: Float, z: Float): String {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude < 5f) return "unknown"

        val nx = x / magnitude
        val ny = y / magnitude
        val nz = z / magnitude
        return when {
            nz >= 0.78f -> "flat_face_up"
            nz <= -0.78f -> "flat_face_down"
            abs(ny) >= 0.78f -> if (ny > 0f) "upright_portrait" else "upside_down_portrait"
            abs(nx) >= 0.78f -> if (nx > 0f) "landscape_left" else "landscape_right"
            else -> "tilted"
        }
    }

    fun classifyLight(lux: Float): String = when {
        !lux.isFinite() || lux < 0f -> "unknown"
        lux < 2f -> "very_dark"
        lux < 20f -> "dim"
        lux < 200f -> "indoor"
        lux < 1_000f -> "bright"
        else -> "very_bright"
    }

    fun motionMagnitude(x: Float, y: Float, z: Float): Float =
        sqrt(x * x + y * y + z * z)

    fun classifyMotion(score: Float): String = when {
        !score.isFinite() || score < 0f -> "unknown"
        score < 0.20f -> "still"
        score < 0.80f -> "slight_motion"
        score < 2.50f -> "moving"
        else -> "intense_motion"
    }

    fun freshness(observedAtMs: Long, nowMs: Long): String {
        if (observedAtMs <= 0L || nowMs < observedAtMs) return "unknown"
        val ageMs = nowMs - observedAtMs
        return when {
            ageMs <= 2 * 60_000L -> "fresh"
            ageMs <= 15 * 60_000L -> "recent"
            else -> "stale"
        }
    }

    fun redactNotificationText(input: CharSequence?, maxLength: Int = 120): String {
        if (input == null || maxLength <= 0) return ""
        var value = sanitizeUntrustedText(input, maxLength.coerceAtLeast(1) * 4)
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[链接已隐藏]")
            .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[邮箱已隐藏]")
            .replace(Regex("(?<!\\d)\\d{6,}(?!\\d)"), "[长数字已隐藏]")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (value.length > maxLength) value = value.take(maxLength - 1) + "…"
        return value
    }

    /**
     * Removes control and bidirectional-formatting characters from text owned
     * by another app. This makes it safe to display/quote, not a trusted
     * instruction.
     */
    fun sanitizeUntrustedText(input: CharSequence?, maxLength: Int): String {
        if (input == null || maxLength <= 0) return ""
        var value = input.toString()
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]"), "")
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (value.length > maxLength) value = value.take(maxLength - 1) + "…"
        return value
    }
}

data class GravityMotionEstimate(
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
    val linearMotionMagnitude: Float?,
    val sampleCount: Int,
)

class GravityMotionEstimator(
    private val alpha: Float = 0.80f,
    private val warmupSamples: Int = 5,
) {
    private val gravity = FloatArray(3)
    private var initialized = false
    private var samples = 0

    fun add(x: Float, y: Float, z: Float): GravityMotionEstimate {
        val values = floatArrayOf(x, y, z)
        if (!initialized) {
            values.copyInto(gravity)
            initialized = true
            samples = 1
        } else {
            for (index in 0..2) {
                gravity[index] = alpha * gravity[index] + (1f - alpha) * values[index]
            }
            samples += 1
        }
        val motion = if (samples >= warmupSamples) {
            DeviceContextLogic.motionMagnitude(
                x - gravity[0],
                y - gravity[1],
                z - gravity[2],
            )
        } else null
        return GravityMotionEstimate(gravity[0], gravity[1], gravity[2], motion, samples)
    }

    fun reset() {
        gravity.fill(0f)
        initialized = false
        samples = 0
    }
}
