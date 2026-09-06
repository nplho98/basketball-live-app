package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.19.0：球員名單解析——存檔與讀取都走 parseRoster，格式契約只在這裡驗。 */
class PlayerRosterTest {

    @Test
    fun `trims blanks and drops empty lines`() {
        assertEquals(
            listOf("王小明", "李大華"),
            StreamPrefs.parseRoster("  王小明  \n\n李大華\n   \n")
        )
    }

    @Test
    fun `normalizes carriage returns from pasted text`() {
        assertEquals(
            listOf("王小明", "李大華", "張三"),
            StreamPrefs.parseRoster("王小明\r\n李大華\r張三")
        )
    }

    @Test
    fun `caps roster at the max size`() {
        val raw = (1..20).joinToString("\n") { "球員$it" }
        val parsed = StreamPrefs.parseRoster(raw)
        assertEquals(StreamPrefs.ROSTER_MAX_SIZE, parsed.size)
        // 上限由常數決定（v0.22.0：12 → 15），別再寫死數字，改上限時測試才會自動跟著
        assertEquals("球員${StreamPrefs.ROSTER_MAX_SIZE}", parsed.last())
    }

    @Test
    fun `blank and null input give empty roster`() {
        assertEquals(emptyList<String>(), StreamPrefs.parseRoster(null))
        assertEquals(emptyList<String>(), StreamPrefs.parseRoster("   \n\n  "))
    }

    @Test
    fun `serialize then parse keeps the same names`() {
        val names = listOf("王小明", "李大華", "張三")
        assertEquals(names, StreamPrefs.parseRoster(StreamPrefs.serializeRoster(names)))
    }

    @Test
    fun `grade options match the settings spinner list`() {
        assertEquals(listOf("七年級", "八年級", "九年級"), StreamPrefs.ROSTER_GRADES)
        assertEquals("七年級", StreamPrefs.DEFAULT_ROSTER_GRADE)
    }
}
