package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateAdaptationWarmupTest {
    @Test fun startKeepsWarmupActiveUntilDeadline() {
        val warmup = BitrateAdaptationWarmup(10_000L)
        warmup.start(1_000L)
        assertTrue(warmup.isActive(1_000L))
        assertEquals(1L, warmup.remainingMs(10_999L))
        assertFalse(warmup.isActive(11_000L))
    }

    @Test fun restartCreatesIndependentEpoch() {
        val warmup = BitrateAdaptationWarmup(10_000L)
        warmup.start(1_000L)
        warmup.start(8_000L)
        assertTrue(warmup.isActive(17_999L))
        assertFalse(warmup.isActive(18_000L))
    }

    @Test fun clearEndsWarmupImmediately() {
        val warmup = BitrateAdaptationWarmup(10_000L)
        warmup.start(1_000L)
        warmup.clear()
        assertFalse(warmup.isActive(1_001L))
        assertEquals(0L, warmup.remainingMs(1_001L))
    }
}
