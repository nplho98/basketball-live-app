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
    fun `名單全部列出掛零也列且維持名單順序`() {
        val book = PlayerStatBook()
        book.add("陳志豪", PlayerStatType.REBOUND, 5)
        val rows = buildPlayerStatRows(
            roster,
            listOf(ScorerTotal("李大同", 8)),
            book
        )
        // v0.22.16：不再依得分重排——名單什麼順序就什麼順序（背號排序在 reloadRoster 做）
        assertEquals(roster, rows.map { it.name })
        assertEquals(0, rows.first { it.name == "王小明" }.points)
        assertEquals(5, rows.first { it.name == "陳志豪" }.rebound)
    }

    @Test
    fun `名單外的人完全不列出未指定固定最後`() {
        // v0.22.0：Boss 指定拿掉「名單外」段（切年級後的前一批人不再出現在表上）
        val book = PlayerStatBook()
        book.add("舊年級球員", PlayerStatType.ASSIST, 2)
        val rows = buildPlayerStatRows(
            roster,
            listOf(ScorerTotal("王小明", 4), ScorerTotal("舊年級球員", 9), ScorerTotal("", 6)),
            book
        )
        assertEquals(roster.size + 1, rows.size)
        assertTrue(rows.none { it.name == "舊年級球員" })
        assertTrue(rows.last().isUnassigned)
        assertEquals(6, rows.last().points)
    }

    @Test
    fun `有得分有數據都不會影響順序一律照名單`() {
        // v0.22.16：得分高低、有沒有記錄都不再影響順序（改成背號小到大，排序在 reloadRoster）
        val names = listOf("全零甲", "只有失誤", "有籃板", "全零乙", "有得分")
        val book = PlayerStatBook()
        book.add("只有失誤", PlayerStatType.TURNOVER, 2)
        book.add("有籃板", PlayerStatType.REBOUND, 3)
        val rows = buildPlayerStatRows(names, listOf(ScorerTotal("有得分", 4)), book)

        assertEquals(names, rows.map { it.name })
    }

    @Test
    fun `成績單模式得分高到低全零沉底同分照背號順序`() {
        // v0.22.16：分享 LINE 與賽果圖用 sortByScore＝true；傳進來已是背號序，
        // 同分時穩定排序會保住背號順序（「三號」在「四號」前面）
        val names = listOf("一號全零", "二號有籃板", "三號同分", "四號同分", "五號得分王")
        val book = PlayerStatBook()
        book.add("二號有籃板", PlayerStatType.REBOUND, 3)
        val rows = buildPlayerStatRows(
            names,
            listOf(
                ScorerTotal("五號得分王", 10),
                ScorerTotal("三號同分", 6),
                ScorerTotal("四號同分", 6)
            ),
            book,
            sortByScore = true
        )

        assertEquals(
            listOf("五號得分王", "三號同分", "四號同分", "二號有籃板", "一號全零"),
            rows.map { it.name }
        )
    }

    @Test
    fun `賽果圖雙欄分流奇數時左欄多一列`() {
        fun rowsOf(count: Int) = (1..count).map { PlayerStatRow("球員$it", 0, 0, 0, 0, 0) }

        val (evenLeft, evenRight) = splitStatRowsIntoColumns(rowsOf(16))
        assertEquals(8, evenLeft.size)
        assertEquals(8, evenRight.size)
        assertEquals("球員1", evenLeft.first().name)
        assertEquals("球員9", evenRight.first().name)

        val (oddLeft, oddRight) = splitStatRowsIntoColumns(rowsOf(15))
        assertEquals(8, oddLeft.size)
        assertEquals(7, oddRight.size)

        val (singleLeft, singleRight) = splitStatRowsIntoColumns(rowsOf(1))
        assertEquals(1, singleLeft.size)
        assertTrue(singleRight.isEmpty())

        val (emptyLeft, emptyRight) = splitStatRowsIntoColumns(emptyList())
        assertTrue(emptyLeft.isEmpty())
        assertTrue(emptyRight.isEmpty())
    }

    @Test
    fun `全形補位讓個位數與兩位數等寬`() {
        assertEquals("　７", toFullWidthPadded(7))
        assertEquals("１０", toFullWidthPadded(10))
        assertEquals("１００", toFullWidthPadded(100))
        assertEquals("　０", toFullWidthPadded(0))
    }

    @Test
    fun `姓名補到指定寬度`() {
        assertEquals("劉衡　", padName("劉衡", 3))
        assertEquals("何祈叡", padName("何祈叡", 3))
    }

    @Test
    fun `失誤也會進統計與資料列`() {
        val book = PlayerStatBook()
        book.add("王小明", PlayerStatType.TURNOVER, 2)
        val restored = PlayerStatBook.fromJsonString(book.toJsonString())
        assertEquals(2, restored.get("王小明", PlayerStatType.TURNOVER))
        val rows = buildPlayerStatRows(listOf("王小明"), emptyList(), restored)
        assertEquals(2, rows.single().turnover)
        assertEquals(2, rows.single().statOf(PlayerStatType.TURNOVER))
    }

    @Test
    fun `名單為空且沒有任何得分時不產生任何列`() {
        assertTrue(buildPlayerStatRows(emptyList(), emptyList(), PlayerStatBook()).isEmpty())
    }
}
