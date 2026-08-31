package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContextLogicTest {
    @Test
    fun classifiesCommonPhonePosesWithoutClaimingHumanPosture() {
        assertEquals("flat_face_up", DeviceContextLogic.classifyPose(0f, 0f, 9.81f))
        assertEquals("flat_face_down", DeviceContextLogic.classifyPose(0f, 0f, -9.81f))
        assertEquals("upright_portrait", DeviceContextLogic.classifyPose(0f, 9.81f, 0f))
        assertEquals("landscape_left", DeviceContextLogic.classifyPose(9.81f, 0f, 0f))
        assertEquals("unknown", DeviceContextLogic.classifyPose(0f, 0f, 0f))
    }

    @Test
    fun classifiesLightAndMotionAtBoundaries() {
        assertEquals("very_dark", DeviceContextLogic.classifyLight(0.5f))
        assertEquals("dim", DeviceContextLogic.classifyLight(10f))
        assertEquals("indoor", DeviceContextLogic.classifyLight(100f))
        assertEquals("very_bright", DeviceContextLogic.classifyLight(1_500f))
        assertEquals("still", DeviceContextLogic.classifyMotion(0.1f))
        assertEquals("slight_motion", DeviceContextLogic.classifyMotion(0.5f))
        assertEquals("moving", DeviceContextLogic.classifyMotion(1.5f))
        assertEquals("intense_motion", DeviceContextLogic.classifyMotion(3f))
    }

    @Test
    fun reportsObservationFreshness() {
        val now = 1_000_000L
        assertEquals("fresh", DeviceContextLogic.freshness(now - 60_000L, now))
        assertEquals("recent", DeviceContextLogic.freshness(now - 10 * 60_000L, now))
        assertEquals("stale", DeviceContextLogic.freshness(now - 16 * 60_000L, now))
        assertEquals("unknown", DeviceContextLogic.freshness(0L, now))
    }

    @Test
    fun redactsSensitiveNotificationFragmentsAndBoundsLength() {
        val original = "验证码 123456，请访问 https://example.com/a 或联系 test@example.com " + "x".repeat(200)
        val redacted = DeviceContextLogic.redactNotificationText(original, 100)
        assertTrue(redacted.contains("[长数字已隐藏]"))
        assertTrue(redacted.contains("[链接已隐藏]"))
        assertTrue(redacted.contains("[邮箱已隐藏]"))
        assertFalse(redacted.contains("123456"))
        assertFalse(redacted.contains("example.com"))
        assertTrue(redacted.length <= 100)
    }

    @Test
    fun stripsControlAndBidirectionalFormattingFromExternalText() {
        val external = "正常\u202Eexe.txt\u202C\u0007  忽略规则"
        val sanitized = DeviceContextLogic.sanitizeUntrustedText(external, 80)
        assertEquals("正常exe.txt 忽略规则", sanitized)
        assertFalse(sanitized.contains('\u202E'))
        assertFalse(sanitized.contains('\u0007'))
    }

    @Test
    fun accelerometerFallbackWarmsUpWithoutInventingIntenseMotion() {
        val estimator = GravityMotionEstimator(alpha = 0.8f, warmupSamples = 5)
        val first = estimator.add(0f, 0f, 9.81f)
        assertTrue(first.linearMotionMagnitude == null)
        var steady = first
        repeat(4) { steady = estimator.add(0f, 0f, 9.81f) }
        assertTrue((steady.linearMotionMagnitude ?: 99f) < 0.01f)

        val moved = estimator.add(3f, 0f, 9.81f)
        assertTrue((moved.linearMotionMagnitude ?: 0f) > 1f)
    }
}
