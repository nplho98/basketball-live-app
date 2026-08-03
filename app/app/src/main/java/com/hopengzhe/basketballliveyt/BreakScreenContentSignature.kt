package com.hopengzhe.basketballliveyt

/** 休息畫面 Bitmap 的完整內容鍵；未列入的狀態不會影響休息畫面像素。 */
data class BreakScreenContentSignature(
    val teamHomeName: String,
    val teamAwayName: String,
    val scoreHome: Int,
    val scoreAway: Int,
    val quarterScoresHome: List<Int>,
    val quarterScoresAway: List<Int>,
    val period: Int,
    val tableColumnCount: Int,
    val baseWidth: Int,
    val baseHeight: Int
) {
    companion object {
        /**
         * 建立不可變的內容簽章。陣列會複製成 List，避免後續原地修改使快取鍵跟著改變。
         */
        fun calculate(
            teamHomeName: String,
            teamAwayName: String,
            scoreHome: Int,
            scoreAway: Int,
            quarterScoresHome: IntArray,
            quarterScoresAway: IntArray,
            period: Int,
            tableColumnCount: Int,
            baseWidth: Int,
            baseHeight: Int
        ): BreakScreenContentSignature = BreakScreenContentSignature(
            teamHomeName = teamHomeName,
            teamAwayName = teamAwayName,
            scoreHome = scoreHome,
            scoreAway = scoreAway,
            quarterScoresHome = quarterScoresHome.toList(),
            quarterScoresAway = quarterScoresAway.toList(),
            period = period,
            tableColumnCount = tableColumnCount,
            baseWidth = baseWidth,
            baseHeight = baseHeight
        )
    }
}
