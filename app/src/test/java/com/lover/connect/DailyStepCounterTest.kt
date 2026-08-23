package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyStepCounterTest {
    @Test
    fun firstSensorValueBecomesAnchor() {
        val state = DailyStepCounter.update(DailyStepState(date = "2026-08-20"), "2026-08-20", 1200)

        assertEquals(0, state.count)
        assertEquals(1200, state.lastSensorTotal)
    }

    @Test
    fun serviceRestartKeepsCountAndAddsMissedDelta() {
        val restored = DailyStepState(date = "2026-08-20", count = 83, lastSensorTotal = 1200)
        val state = DailyStepCounter.update(restored, "2026-08-20", 1217)

        assertEquals(100, state.count)
        assertEquals(1217, state.lastSensorTotal)
    }

    @Test
    fun phoneRebootKeepsDailyCountAndReanchors() {
        val beforeReboot = DailyStepState(date = "2026-08-20", count = 83, lastSensorTotal = 1200)
        val afterReboot = DailyStepCounter.update(beforeReboot, "2026-08-20", 4)
        val afterMoreSteps = DailyStepCounter.update(afterReboot, "2026-08-20", 11)

        assertEquals(83, afterReboot.count)
        assertEquals(90, afterMoreSteps.count)
    }

    @Test
    fun newDateStartsAtZeroWithFreshAnchor() {
        val yesterday = DailyStepState(date = "2026-08-19", count = 6543, lastSensorTotal = 9000)
        val today = DailyStepCounter.update(yesterday, "2026-08-20", 9010)

        assertEquals(DailyStepState("2026-08-20", 0, 9010), today)
    }
}
