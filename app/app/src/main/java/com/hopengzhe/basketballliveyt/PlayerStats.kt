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
    /** v0.21.0：顯示名稱由「阻攻」改「火鍋」（Boss 指定），jsonKey 維持 blk 以相容既有存檔。 */
    BLOCK("blk"),
    STEAL("stl"),
    TURNOVER("tov")
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
    val turnover: Int = 0,
    val isUnassigned: Boolean = false
) {
    fun statOf(type: PlayerStatType): Int = when (type) {
        PlayerStatType.REBOUND -> rebound
        PlayerStatType.ASSIST -> assist
        PlayerStatType.BLOCK -> block
        PlayerStatType.STEAL -> steal
        PlayerStatType.TURNOVER -> turnover
    }

    /**
     * 這一場有沒有任何記錄。**只有失誤也算有**（Boss 2026-09-06 拍板）——
     * 這一層要分的是「有沒有上場」，表現好壞已經由得分那一層排過了。
     */
    fun hasAnyRecord(): Boolean =
        points > 0 || PlayerStatType.entries.any { statOf(it) > 0 }
}

/**
 * v0.21.0：貼到 LINE／YouTube 的對齊——**數字改全形、個位數前補一個全形空白**。
 *
 * LINE 用比例字體：中文字是全形（1 em），半形數字只有半個 em，所以「籃板7」與「籃板10」
 * 差的是半個字寬，補半形空白也補不準（多數字體的空白比數字還窄）。
 * 全形數字與全形空白都剛好 1 em，補起來每欄固定兩個字寬，上下才對得齊。
 */
fun toFullWidthPadded(value: Int, width: Int = 2): String {
    val digits = value.toString().map { char ->
        if (char in '0'..'9') char + 0xFEE0 else char
    }.joinToString("")
    return "\u3000".repeat((width - digits.length).coerceAtLeast(0)) + digits
}

/** 姓名長度不一（兩字／三字）同樣會讓後面整排歪掉，補全形空白到本場最長的姓名寬度。 */
fun padName(name: String, width: Int): String =
    name + "\u3000".repeat((width - name.length).coerceAtLeast(0))

/**
 * 球員數據表：**目前名單全部列出（整場掛零也列，Boss 指定）**，得分的「未指定」固定最後一列。
 * 排序三層（Boss 2026-09-06）：
 * 1. 得分高到低
 * 2. 得分相同時，**有任何記錄的排前面、六項全零的沉到最底**（只有失誤也算有記錄）
 * 3. 同一組維持名單順序（`sortedWith` 是穩定排序）
 *
 * v0.22.0：**移除「名單外」段**（Boss 2026-09-06 指定，球員數據視窗／分享 LINE／輸出圖片三處一致）。
 * 代價是直播中切年級後，前一批球員的數據不再顯示（存檔仍留著）；
 * Boss 確認直播中不切年級，故不做防護。
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
        steal = book.get(name, PlayerStatType.STEAL),
        turnover = book.get(name, PlayerStatType.TURNOVER)
    )

    val inRoster = roster.map { rowOf(it) }
        .sortedWith(
            compareByDescending<PlayerStatRow> { it.points }
                .thenByDescending { it.hasAnyRecord() }
        )
    val unassigned = pointsOf[""]?.takeIf { it > 0 }
        ?.let { listOf(PlayerStatRow("", it, 0, 0, 0, 0, 0, isUnassigned = true)) }
        ?: emptyList()
    return inRoster + unassigned
}

/**
 * v0.22.0：賽果圖的雙欄分流——前半列進左欄、後半列進右欄，奇數時左欄多一列。
 * 抽成純函式是為了單元測試（畫圖那段有 Canvas 相依測不到）。
 */
fun splitStatRowsIntoColumns(rows: List<PlayerStatRow>): Pair<List<PlayerStatRow>, List<PlayerStatRow>> {
    val leftCount = (rows.size + 1) / 2
    return rows.take(leftCount) to rows.drop(leftCount)
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
