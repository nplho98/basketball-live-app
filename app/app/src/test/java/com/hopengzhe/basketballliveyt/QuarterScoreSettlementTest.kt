package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class QuarterScoreSettlementTest {

    @Test
    fun `第二節進行中誤按減再加不會把得分併入第一節`() {
        val home = intArrayOf(12, -1, -1, -1)
        val away = intArrayOf(10, -1, -1, -1)

        QuarterScoreSettlement.settleOnRewind(1, home, away)
        QuarterScoreSettlement.settleOnAdvance(1, 20, 16, home, away)

        assertArrayEquals(intArrayOf(12, -1, -1, -1), home)
        assertArrayEquals(intArrayOf(10, -1, -1, -1), away)
    }

    @Test
    fun `加減加仍保留第二節原結算`() {
        val home = intArrayOf(12, -1, -1, -1)
        val away = intArrayOf(10, -1, -1, -1)

        QuarterScoreSettlement.settleOnAdvance(2, 20, 16, home, away)
        QuarterScoreSettlement.settleOnRewind(2, home, away)
        QuarterScoreSettlement.settleOnAdvance(2, 20, 16, home, away)

        assertArrayEquals(intArrayOf(12, 8, -1, -1), home)
        assertArrayEquals(intArrayOf(10, 6, -1, -1), away)
    }

    @Test
    fun `第一節下限按減不得改變任何結算`() {
        val home = intArrayOf(12, -1, -1, -1)
        val away = intArrayOf(10, -1, -1, -1)

        // changePeriod 在下限會先 return；即使防禦性呼叫回退核心也必須是 no-op。
        QuarterScoreSettlement.settleOnRewind(1, home, away)

        assertArrayEquals(intArrayOf(12, -1, -1, -1), home)
        assertArrayEquals(intArrayOf(10, -1, -1, -1), away)
    }

    @Test
    fun `第五至第七節皆可結算且第七節以外不變`() {
        val home = intArrayOf(12, 8, 14, 10, -1, -1, -1)
        val away = intArrayOf(10, 6, 12, 10, -1, -1, -1)

        QuarterScoreSettlement.settleOnAdvance(5, 51, 43, home, away)
        QuarterScoreSettlement.settleOnAdvance(6, 58, 49, home, away)
        QuarterScoreSettlement.settleOnAdvance(7, 63, 53, home, away)
        QuarterScoreSettlement.settleOnAdvance(8, 99, 99, home, away)

        assertArrayEquals(intArrayOf(12, 8, 14, 10, 7, 7, 5), home)
        assertArrayEquals(intArrayOf(10, 6, 12, 10, 5, 6, 4), away)
    }
}
