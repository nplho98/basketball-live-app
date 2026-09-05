package com.hopengzhe.basketballliveyt

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * v0.20.0：四項球員數據（籃板／助攻／阻攻／抄截），見開發方案
 * `100_Todo/projects/Project14_四項數據統計_2026-09-05_v1.2.md`。
 *
 * 與得分（[HighlightMarker]）的根本差別：**這四項不產生精彩標記**，不進 YouTube 章節、不動計分板，
 * 唯一產物是統計數字。因此也沒有「未指定」——按下按鈕當下什麼都不寫，選到名字才累加，
 * 任何關窗路徑（按計分鈕、返回鍵、再按一顆數據鈕、收播、進休息畫面）都自然作廢。
 */
enum class PlayerStatType(val jsonKey: String) {
    REBOUND("reb"),
    ASSIST("ast"),
    BLOCK("blk"),
    STEAL("stl")
}

/**
 * 以球員姓名字串為索引的四項計數（與 [HighlightMarker.scorer] 同一套索引）。
 * 不含 Android 相依，供單元測試直接使用；存讀檔在 [PlayerStatStore]。
 */
class PlayerStatBook {

    private val counts = LinkedHashMap<String, IntArray>()

    private fun slots(name: String): IntArray =
        counts.getOrPut(name) { IntArray(PlayerStatType.entries.size) }

    fun get(name: String, type: PlayerStatType): Int =
        counts[name]?.get(type.ordinal) ?: 0

    /** 加減後回傳新值；下限夾在 0（Boss 拍板：−1 不得減成負數）。 */
    fun add(name: String, type: PlayerStatType, delta: Int): Int {
        val slots = slots(name)
        val next = (slots[type.ordinal] + delta).coerceAtLeast(0)
        slots[type.ordinal] = next
        return next
    }

    /** 有任何一項不為 0 的姓名（依首次出現順序）。 */
    fun namesWithData(): List<String> =
        counts.filterValues { slots -> slots.any { it > 0 } }.keys.toList()

    fun toJsonString(): String {
        val root = JSONObject()
        counts.forEach { (name, slots) ->
            if (slots.any { it > 0 }) {
                val obj = JSONObject()
                PlayerStatType.entries.forEach { obj.put(it.jsonKey, slots[it.ordinal]) }
                root.put(name, obj)
            }
        }
        return root.toString()
    }

    companion object {
        fun fromJsonString(text: String): PlayerStatBook {
            val book = PlayerStatBook()
            try {
                val root = JSONObject(text)
                root.keys().forEach { name ->
                    val obj = root.getJSONObject(name)
                    PlayerStatType.entries.forEach { type ->
                        book.add(name, type, obj.optInt(type.jsonKey, 0))
                    }
                }
            } catch (e: Exception) {
                // 壞檔當作沒有統計，不影響直播（同 HighlightStore.load）
            }
            return book
        }
    }
}

/** 球員數據表的一列：姓名＋得分＋四項。[isUnassigned] 為得分的「未指定」列（四項永遠不會有這種列）。 */
data class PlayerStatRow(
    val name: String,
    val points: Int,
    val rebound: Int,
    val assist: Int,
    val block: Int,
    val steal: Int,
    val isUnassigned: Boolean = false
) {
    fun statOf(type: PlayerStatType): Int = when (type) {
        PlayerStatType.REBOUND -> rebound
        PlayerStatType.ASSIST -> assist
        PlayerStatType.BLOCK -> block
        PlayerStatType.STEAL -> steal
    }
}

/**
 * 球員數據表：**名單 12 人全部列出（整場掛零也列，Boss 指定）**，
 * 名單外但有數據的人（切年級後的前一批）接在後面，得分的「未指定」固定最後一列。
 * 排序：名單內依得分高到低，同分維持名單順序。
 */
fun buildPlayerStatRows(
    roster: List<String>,
    scorerTotals: List<ScorerTotal>,
    book: PlayerStatBook
): List<PlayerStatRow> {
    val pointsOf = scorerTotals.associate { it.scorer to it.points }
    fun rowOf(name: String) = PlayerStatRow(
        name = name,
        points = pointsOf[name] ?: 0,
        rebound = book.get(name, PlayerStatType.REBOUND),
        assist = book.get(name, PlayerStatType.ASSIST),
        block = book.get(name, PlayerStatType.BLOCK),
        steal = book.get(name, PlayerStatType.STEAL)
    )

    val inRoster = roster.map { rowOf(it) }
        .sortedWith(compareByDescending<PlayerStatRow> { it.points })
    val outsiders = (pointsOf.keys.filter { it.isNotEmpty() } + book.namesWithData())
        .distinct()
        .filter { it !in roster }
        .map { rowOf(it) }
        .sortedWith(compareByDescending<PlayerStatRow> { it.points })
    val unassigned = pointsOf[""]?.takeIf { it > 0 }
        ?.let { listOf(PlayerStatRow("", it, 0, 0, 0, 0, isUnassigned = true)) }
        ?: emptyList()
    return inRoster + outsiders + unassigned
}

/**
 * 四項數據存檔——與精彩標記共用場次時間戳與 `highlights/` 資料夾（[HighlightStore]），
 * 檔名 `stats_<場次時間戳>.json`，收播不清除、跨場次互不覆蓋。
 * 存檔失敗只吞例外不影響直播，做法與 [HighlightStore.save] 一致（見開發方案「已知限制」）。
 */
object PlayerStatStore {

    private fun statsFile(context: Context, sessionTimestamp: String): File {
        val dir = File(context.getExternalFilesDir(null), "highlights")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "stats_$sessionTimestamp.json")
    }

    fun save(context: Context, sessionTimestamp: String, book: PlayerStatBook) {
        try {
            statsFile(context, sessionTimestamp).writeText(book.toJsonString())
        } catch (e: Exception) {
            // 同 HighlightStore：統計存檔失敗不影響直播本身
        }
    }

    fun load(context: Context, sessionTimestamp: String): PlayerStatBook {
        val file = statsFile(context, sessionTimestamp)
        if (!file.exists()) return PlayerStatBook()
        return try {
            PlayerStatBook.fromJsonString(file.readText())
        } catch (e: Exception) {
            PlayerStatBook()
        }
    }
}
