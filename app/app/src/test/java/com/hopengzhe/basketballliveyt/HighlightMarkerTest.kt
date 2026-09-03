package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.18.15：標記顯示格式與 JSON 來回（含舊檔無 label 的相容）。 */
class HighlightMarkerTest {

    @Test
    fun `有說明文字時顯示時間加說明`() {
        val marker = HighlightMarker(95_000L, 2, 12, 8, "信義N號：2分")
        assertEquals("01:35 信義N號：2分", marker.toDisplayLine())
    }

    @Test
    fun `舊檔沒有說明文字時退回節數比分格式`() {
        val marker = HighlightMarker(95_000L, 2, 12, 8)
        assertEquals("01:35 第2節 主12-客8", marker.toDisplayLine())
    }

    // ---------- v0.19.1：本場得分統計 ----------

    private fun scored(scorer: String, points: Int) =
        HighlightMarker(0L, 1, 0, 0, "$scorer：${points}分", scorer, points)

    @Test
    fun `同一人多球加總並由高分排到低分`() {
        val totals = summarizeScorers(
            listOf(scored("王小明", 2), scored("李大華", 3), scored("王小明", 3))
        )
        assertEquals(listOf(ScorerTotal("王小明", 5), ScorerTotal("李大華", 3)), totals)
    }

    @Test
    fun `沒選人的歸未指定且固定排最後`() {
        val totals = summarizeScorers(
            listOf(scored("", 2), scored("", 3), scored("王小明", 1))
        )
        assertEquals(listOf(ScorerTotal("王小明", 1), ScorerTotal("", 5)), totals)
    }

    @Test
    fun `舊標記沒有分數欄位不列入統計`() {
        val totals = summarizeScorers(listOf(HighlightMarker(0L, 1, 0, 0, "信義N號：2分")))
        assertEquals(emptyList<ScorerTotal>(), totals)
    }

    @Test
    fun `同分時依姓名排序不會隨輸入順序跳動`() {
        // 中文以字元碼位排序（李 U+674E 在 王 U+738B 之前），這裡只要求同分時順序穩定
        val expected = listOf(ScorerTotal("李大華", 2), ScorerTotal("王小明", 2))
        assertEquals(expected, summarizeScorers(listOf(scored("李大華", 2), scored("王小明", 2))))
        assertEquals(expected, summarizeScorers(listOf(scored("王小明", 2), scored("李大華", 2))))
    }
}
