package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.20.0：四項球員數據的純函式檢查（不碰 Android，見 PlayerStats.kt）。 */
class PlayerStatsTest {

    private val roster = listOf("王小明", "李大同", "陳志豪")

    @Test
    fun `減到零就停住不會變負數`() {
        val book = PlayerStatBook()
        book.add("王小明", PlayerStatType.REBOUND, 1)
        assertEquals(0, book.add("王小明", PlayerStatType.REBOUND, -1))
        assertEquals(0, book.add("王小明", PlayerStatType.REBOUND, -1))
    }

    @Test
    fun `存讀往返後四項數字一致`() {
        val book = PlayerStatBook()
        book.add("王小明", PlayerStatType.REBOUND, 3)
        book.add("王小明", PlayerStatType.STEAL, 2)
        book.add("李大同", PlayerStatType.ASSIST, 1)
        val restored = PlayerStatBook.fromJsonString(book.toJsonString())
        assertEquals(3, restored.get("王小明", PlayerStatType.REBOUND))
        assertEquals(2, restored.get("王小明", PlayerStatType.STEAL))
        assertEquals(1, restored.get("李大同", PlayerStatType.ASSIST))
        assertEquals(0, restored.get("李大同", PlayerStatType.BLOCK))
    }

    @Test
    fun `壞檔當作沒有統計不丟例外`() {
        assertTrue(PlayerStatBook.fromJsonString("not json").namesWithData().isEmpty())
    }

    @Test
    fun `名單全部列出掛零也列且依得分排序`() {
        val book = PlayerStatBook()
        book.add("陳志豪", PlayerStatType.REBOUND, 5)
        val rows = buildPlayerStatRows(
            roster,
            listOf(ScorerTotal("李大同", 8)),
            book
        )
        assertEquals(listOf("李大同", "王小明", "陳志豪"), rows.map { it.name })
        assertEquals(0, rows.first { it.name == "王小明" }.points)
        assertEquals(5, rows.first { it.name == "陳志豪" }.rebound)
    }

    @Test
    fun `名單外有數據的人接在名單後面未指定固定最後`() {
        val book = PlayerStatBook()
        book.add("舊年級球員", PlayerStatType.ASSIST, 2)
        val rows = buildPlayerStatRows(
            roster,
            listOf(ScorerTotal("王小明", 4), ScorerTotal("", 6)),
            book
        )
        assertEquals(roster.size + 2, rows.size)
        assertEquals("舊年級球員", rows[roster.size].name)
        assertTrue(rows.last().isUnassigned)
        assertEquals(6, rows.last().points)
    }

    @Test
    fun `名單為空且沒有任何得分時不產生任何列`() {
        assertTrue(buildPlayerStatRows(emptyList(), emptyList(), PlayerStatBook()).isEmpty())
    }
}
