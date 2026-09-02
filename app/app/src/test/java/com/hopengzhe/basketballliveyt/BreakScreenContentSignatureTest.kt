package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BreakScreenContentSignatureTest {

    private fun signature(
        scoreHome: Int = 12,
        period: Int = 2,
        tableColumnCount: Int = 4,
        eventTitle: String = "2026 中正盃"
    ) = BreakScreenContentSignature.calculate(
        eventTitle = eventTitle,
        teamHomeName = "主隊",
        teamAwayName = "客隊",
        scoreHome = scoreHome,
        scoreAway = 10,
        quarterScoresHome = intArrayOf(8, -1, -1, -1, -1, -1, -1),
        quarterScoresAway = intArrayOf(6, -1, -1, -1, -1, -1, -1),
        period = period,
        tableColumnCount = tableColumnCount,
        baseWidth = 1920,
        baseHeight = 1080
    )

    @Test
    fun `分數改變時簽章改變`() {
        assertNotEquals(signature(scoreHome = 12), signature(scoreHome = 13))
    }

    @Test
    fun `節數與延長賽欄數改變時簽章改變`() {
        assertNotEquals(
            signature(period = 4, tableColumnCount = 4),
            signature(period = 5, tableColumnCount = 5)
        )
    }

    @Test
    fun `賽事名稱改變時簽章改變`() {
        assertNotEquals(signature(eventTitle = "2026 中正盃"), signature(eventTitle = "2026 信義盃"))
    }

    @Test
    fun `只有牛眨眼格數改變時簽章不變`() {
        val signaturesByBlinkFrame = listOf(-1, 0, 1, 2, 3, 4).map { signature() }

        signaturesByBlinkFrame.forEach { assertEquals(signaturesByBlinkFrame.first(), it) }
    }

    @Test
    fun `簽章會保存節次分數快照`() {
        val quarterScores = intArrayOf(8, -1, -1, -1)
        val original = BreakScreenContentSignature.calculate(
            "2026 中正盃", "主隊", "客隊", 12, 10, quarterScores, intArrayOf(6, -1, -1, -1),
            2, 4, 1920, 1080
        )

        quarterScores[0] = 9

        assertEquals(listOf(8, -1, -1, -1), original.quarterScoresHome)
    }
}
