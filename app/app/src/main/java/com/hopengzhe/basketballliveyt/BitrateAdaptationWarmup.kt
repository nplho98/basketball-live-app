package com.hopengzhe.basketballliveyt

/** 每次 RTMP 連線成功後，暫停餵入 BitrateAdapter 的暖機 epoch。 */
class BitrateAdaptationWarmup(private val durationMs: Long) {
    init { require(durationMs >= 0) { "durationMs 必須大於或等於 0" } }

    @Volatile
    private var deadlineElapsedMs = 0L

    fun start(nowElapsedMs: Long) {
        deadlineElapsedMs = if (durationMs > Long.MAX_VALUE - nowElapsedMs) Long.MAX_VALUE
        else nowElapsedMs + durationMs
    }

    fun clear() { deadlineElapsedMs = 0L }

    fun remainingMs(nowElapsedMs: Long): Long = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    fun isActive(nowElapsedMs: Long): Boolean = remainingMs(nowElapsedMs) > 0L
}
