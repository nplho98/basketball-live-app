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
}
