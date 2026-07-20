package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectRecoveryGateTest {
    @Test fun opensOnlyAfterConsecutiveHealthySamples() {
        val gate = ReconnectRecoveryGate(20_000, 0.7, 3)
        gate.start(1_000)
        assertFalse(gate.observe(2_400_000, 3_300_000))
        assertFalse(gate.observe(1_000_000, 3_300_000))
        assertFalse(gate.observe(2_400_000, 3_300_000))
        assertFalse(gate.observe(2_500_000, 3_300_000))
        assertTrue(gate.observe(2_600_000, 3_300_000))
        assertFalse(gate.isActive)
    }

    @Test fun timeoutAppliesOnlyWhileActive() {
        val gate = ReconnectRecoveryGate(20_000, 0.7, 3)
        gate.start(1_000)
        assertFalse(gate.isTimedOut(20_999))
        assertTrue(gate.isTimedOut(21_000))
        gate.clear()
        assertFalse(gate.isTimedOut(99_000))
    }
}
