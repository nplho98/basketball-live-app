package com.hopengzhe.basketballliveyt

class ReconnectRecoveryGate(
    private val timeoutMs: Long,
    private val healthyRatio: Double,
    private val requiredHealthySamples: Int
) {
    private var startedAtMs = 0L
    private var consecutiveHealthySamples = 0
    var isActive: Boolean = false
        private set

    fun start(nowMs: Long) {
        startedAtMs = nowMs
        consecutiveHealthySamples = 0
        isActive = true
    }

    fun clear() {
        consecutiveHealthySamples = 0
        isActive = false
    }

    fun observe(actualBps: Long, targetBps: Int): Boolean {
        if (!isActive || actualBps <= 0 || targetBps <= 0) return false
        if (actualBps.toDouble() / targetBps >= healthyRatio) {
            consecutiveHealthySamples++
            if (consecutiveHealthySamples >= requiredHealthySamples) {
                isActive = false
                return true
            }
        } else {
            consecutiveHealthySamples = 0
        }
        return false
    }

    fun isTimedOut(nowMs: Long): Boolean = isActive && nowMs - startedAtMs >= timeoutMs
}
