package com.hopengzhe.basketballliveyt

/** 各節分數的純 Kotlin 結算邏輯，與 Android UI 分離以便單元測試。 */
internal object QuarterScoreSettlement {

    fun settleOnAdvance(
        finishedPeriod: Int,
        scoreHome: Int,
        scoreAway: Int,
        quarterScoresHome: IntArray,
        quarterScoresAway: IntArray
    ) {
        val index = finishedPeriod - 1
        if (index !in quarterScoresHome.indices || index !in quarterScoresAway.indices) return

        // 已結算代表只是節數誤觸後返回，不得用包含下一節得分的總分覆寫舊值。
        if (quarterScoresHome[index] < 0) {
            val settledHome = quarterScoresHome.filter { it >= 0 }.sum()
            quarterScoresHome[index] = (scoreHome - settledHome).coerceAtLeast(0)
        }
        if (quarterScoresAway[index] < 0) {
            val settledAway = quarterScoresAway.filter { it >= 0 }.sum()
            quarterScoresAway[index] = (scoreAway - settledAway).coerceAtLeast(0)
        }
    }

    /** 回退只改目前節數；既有結算是不可由目前總分重建的歷史資料，必須保留。 */
    fun settleOnRewind(
        @Suppress("UNUSED_PARAMETER") newPeriod: Int,
        @Suppress("UNUSED_PARAMETER") quarterScoresHome: IntArray,
        @Suppress("UNUSED_PARAMETER") quarterScoresAway: IntArray
    ) = Unit
}
