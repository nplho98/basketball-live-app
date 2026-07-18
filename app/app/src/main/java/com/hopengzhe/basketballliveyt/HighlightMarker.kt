package com.hopengzhe.basketballliveyt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 精彩時刻標記（功能 A，見計畫書「第二波：三項新功能」）。
 *
 * [timestampMs]：直播經過時間（毫秒，已扣掉設定頁「標記回推秒數」），供功能 B 回放定位；
 * [period]/[scoreHome]/[scoreAway]：標記當下的節數與比分，供清單顯示與章節格式輸出。
 */
data class HighlightMarker(
    val timestampMs: Long,
    val period: Int,
    val scoreHome: Int,
    val scoreAway: Int
) {
    /** 顯示／複製章節格式共用：`mm:ss 第N節 主X-客Y`（見計畫書功能 A 清單顯示格式）。 */
    fun toDisplayLine(): String {
        val totalSeconds = (timestampMs / 1000L).coerceAtLeast(0L)
        val mm = totalSeconds / 60
        val ss = totalSeconds % 60
        return String.format(Locale.TAIWAN, "%02d:%02d 第%d節 主%d-客%d", mm, ss, period, scoreHome, scoreAway)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("period", period)
        put("scoreHome", scoreHome)
        put("scoreAway", scoreAway)
    }

    companion object {
        fun fromJson(json: JSONObject): HighlightMarker = HighlightMarker(
            timestampMs = json.optLong("timestampMs", 0L),
            period = json.optInt("period", 1),
            scoreHome = json.optInt("scoreHome", 0),
            scoreAway = json.optInt("scoreAway", 0)
        )
    }
}

/**
 * 精彩時刻標記存檔——JSON 存 APP 外部檔案區 `highlights/` 資料夾，檔名帶場次開播時間戳
 * （見 [sessionFileName]），收播不清除、跨場次互不覆蓋，供功能 B（休息畫面回放）與日後剪輯讀取。
 * 用 Android 內建 org.json，不需額外依賴。
 */
object HighlightStore {

    /** 每次開播（[LiveActivity.beginRtmpStreaming]）呼叫一次，取得本場次唯一的檔名時間戳。 */
    fun newSessionTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())

    private fun highlightsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "highlights")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sessionFile(context: Context, sessionTimestamp: String): File =
        File(highlightsDir(context), "highlights_$sessionTimestamp.json")

    /** 全量覆寫存檔（標記數量少，每次異動直接整份重寫最簡單，不做增量更新）。 */
    fun save(context: Context, sessionTimestamp: String, markers: List<HighlightMarker>) {
        val array = JSONArray()
        markers.forEach { array.put(it.toJson()) }
        try {
            sessionFile(context, sessionTimestamp).writeText(array.toString())
        } catch (e: Exception) {
            // 標記存檔失敗不影響直播本身，鐵律同錄影備份（見 LiveActivity 類別頂端 KDoc）
        }
    }

    fun load(context: Context, sessionTimestamp: String): List<HighlightMarker> {
        val file = sessionFile(context, sessionTimestamp)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { HighlightMarker.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
