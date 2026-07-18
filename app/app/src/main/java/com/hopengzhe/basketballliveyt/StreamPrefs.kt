package com.hopengzhe.basketballliveyt

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直播/串流相關設定的本機儲存（SharedPreferences）。
 *
 * 「串流金鑰」此階段為開發測試入口：由設定頁手動輸入並存在本機，
 * 正式版將改為 YouTube 帳號登入後由 YouTube Data API 自動取得，屆時此欄位會移除。
 */
object StreamPrefs {

    private const val PREFS_NAME = "stream_settings"

    private const val KEY_STREAM_TITLE = "stream_title"
    private const val KEY_PRIVACY = "privacy"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_BITRATE_AUTO_ADJUST = "bitrate_auto_adjust"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_TEAM_HOME_NAME = "team_home_name"
    private const val KEY_TEAM_AWAY_NAME = "team_away_name"
    private const val KEY_EVENT_NAME = "event_name"

    // v0.16.0：功能一——直播標題雙模板（見計畫書功能一／SettingsActivity 標題區塊）。
    // 兩個可各自編輯保存的模板＋單選鈕選用哪個；實際開播標題仍取自「本次直播標題」欄
    //（KEY_STREAM_TITLE），模板只是切換時把文字帶入該欄供微調，微調不回寫模板。
    private const val KEY_TITLE_TEMPLATE_1 = "title_template_1"
    private const val KEY_TITLE_TEMPLATE_2 = "title_template_2"
    private const val KEY_TITLE_TEMPLATE_SELECTED = "title_template_selected"

    // v0.16.0：功能三——休息畫面四節計分表的各節已結算分數（-1＝該節尚未結算），
    // 切節數當下結算（見 LiveActivity.changePeriod），持久化防閃退／重開恢復，
    // 開播確認框勾「重置計分板」時一併清空（見 LiveActivity.resetScoreboardForNewGame）。
    private const val KEY_QUARTER_SCORES_HOME = "quarter_scores_home"
    private const val KEY_QUARTER_SCORES_AWAY = "quarter_scores_away"
    const val QUARTER_COUNT = 4

    // v0.13.0：功能 A 精彩時刻標記——回推秒數（見 LiveActivity 類別頂端 KDoc）
    private const val KEY_HIGHLIGHT_REBOUND_SECONDS = "highlight_rebound_seconds"
    const val DEFAULT_HIGHLIGHT_REBOUND_SECONDS = "10 秒"

    // v0.15.1：孤兒直播回收——開播成功時存 broadcastId，收播「確認 YouTube 已收到結束指令」才清；
    // APP 當掉/被系統殺掉時 ID 會殘留，下次啟動 LiveActivity 自動補送結束指令
    //（見 LiveActivity.endOrphanBroadcastIfAny）
    private const val KEY_PENDING_BROADCAST_ID = "pending_broadcast_id"

    // v0.10.0：同步錄影備份設定（見 LiveActivity 類別頂端 KDoc／SettingsActivity 對應區塊）
    private const val KEY_RECORD_ENABLED = "record_enabled"
    private const val KEY_RECORD_RESOLUTION = "record_resolution"
    private const val KEY_RECORD_SAVE_MODE = "record_save_mode"
    private const val KEY_RECORD_TREE_URI = "record_tree_uri"

    // 預設值需與 strings.xml 內對應 string-array 的項目文字完全一致，
    // 才能在設定頁還原上次選擇的 Spinner 選項。
    const val DEFAULT_RESOLUTION = "1280x720（720p）"
    const val DEFAULT_FPS = "30 fps"
    const val DEFAULT_BITRATE = "2300 Kbps"

    // v0.10.0：同步錄影備份選項文字，需與 strings.xml 的 record_resolution_options／
    // record_save_mode_options 完全一致（Spinner 還原機制，沿用上面既有慣例）
    const val RECORD_RESOLUTION_SAME_AS_LIVE = "與直播相同"
    const val RECORD_SAVE_MODE_GALLERY = "相簿"
    const val RECORD_SAVE_MODE_CUSTOM_FOLDER = "自訂資料夾…"

    // v0.15.5：已移除功能殘留的死鍵——+3 特效（v0.14.1 拆）與公牛動畫開關（v0.11.x），
    // 現存程式碼已無任何讀寫，只剩舊裝置 SharedPreferences 內的殘值，啟動時清掉
    // v0.15.6：VBR 開關移除（實質只影響碼率顯示平滑，名不副實，Boss 拍板移除），鍵一併清掉
    private val DEPRECATED_KEYS = listOf("home_plus3_celebration", "bull_anim_enabled", "vbr")

    fun purgeDeprecatedKeys(context: Context) {
        val p = prefs(context)
        val staleKeys = DEPRECATED_KEYS.filter { p.contains(it) }
        if (staleKeys.isEmpty()) return
        p.edit().apply { staleKeys.forEach { remove(it) } }.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        streamTitle: String,
        privacy: String,
        resolution: String,
        fps: String,
        bitrate: String,
        bitrateAutoAdjust: Boolean,
        streamKey: String,
        recordEnabled: Boolean,
        recordResolution: String,
        recordSaveMode: String
    ) {
        prefs(context).edit()
            .putString(KEY_STREAM_TITLE, streamTitle)
            .putString(KEY_PRIVACY, privacy)
            .putString(KEY_RESOLUTION, resolution)
            .putString(KEY_FPS, fps)
            .putString(KEY_BITRATE, bitrate)
            .putBoolean(KEY_BITRATE_AUTO_ADJUST, bitrateAutoAdjust)
            .putString(KEY_STREAM_KEY, streamKey)
            .putBoolean(KEY_RECORD_ENABLED, recordEnabled)
            .putString(KEY_RECORD_RESOLUTION, recordResolution)
            .putString(KEY_RECORD_SAVE_MODE, recordSaveMode)
            .apply()
    }

    fun getStreamTitle(context: Context): String = prefs(context).getString(KEY_STREAM_TITLE, "") ?: ""

    /** 建立直播用的標題：設定頁空白時給預設「籃球直播＋當天日期」。 */
    fun getStreamTitleOrDefault(context: Context): String {
        val saved = getStreamTitle(context).trim()
        if (saved.isNotEmpty()) return saved
        val today = SimpleDateFormat("MM/dd", Locale.TAIWAN).format(Date())
        return "籃球直播 $today"
    }

    fun getPrivacy(context: Context): String = prefs(context).getString(KEY_PRIVACY, "") ?: ""

    fun getResolution(context: Context): String =
        prefs(context).getString(KEY_RESOLUTION, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION

    fun getFps(context: Context): String =
        prefs(context).getString(KEY_FPS, DEFAULT_FPS) ?: DEFAULT_FPS

    fun getBitrate(context: Context): String =
        prefs(context).getString(KEY_BITRATE, DEFAULT_BITRATE) ?: DEFAULT_BITRATE

    /** 直播中碼率自動調整開關：true＝網路壅塞時自動降碼率（見 LiveActivity.onNewBitrate），
     *  false＝全程固定用使用者選的碼率，不受 BitrateAdapter 影響。 */
    fun isBitrateAutoAdjust(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BITRATE_AUTO_ADJUST, true)

    /** 開發測試用的 YouTube 串流金鑰，未來會改由帳號登入自動取得。 */
    fun getStreamKey(context: Context): String = prefs(context).getString(KEY_STREAM_KEY, "") ?: ""

    /** 解析解析度字串（例如「1280x720（720p）」）成寬高 px，解析失敗則回傳 720p 預設值。 */
    fun parseResolution(resolution: String): Pair<Int, Int> {
        val match = Regex("""(\d+)x(\d+)""").find(resolution)
        val width = match?.groupValues?.get(1)?.toIntOrNull() ?: 1280
        val height = match?.groupValues?.get(2)?.toIntOrNull() ?: 720
        return width to height
    }

    /** 解析 fps 字串（例如「30 fps」）成整數，解析失敗則回傳 30。 */
    fun parseFps(fps: String): Int {
        return Regex("""(\d+)""").find(fps)?.value?.toIntOrNull() ?: 30
    }

    /** 解析碼率字串（例如「2300 Kbps」）成 bps，解析失敗則回傳 2300 Kbps。 */
    fun parseBitrate(bitrate: String): Int {
        val kbps = Regex("""(\d+)""").find(bitrate)?.value?.toIntOrNull() ?: 2300
        return kbps * 1000
    }

    /** 將設定頁的隱私選項（公開/不公開/私人）轉成 YouTube Data API 的 privacyStatus 值。 */
    fun mapPrivacyToApiValue(privacyLabel: String): String = when (privacyLabel) {
        "公開" -> "public"
        "私人" -> "private"
        else -> "unlisted" // 涵蓋「不公開」與尚未設定的情況
    }

    /** 主隊／客隊自訂隊名（空字串代表沿用預設「主隊」「客隊」）。 */
    fun getTeamHomeName(context: Context): String = prefs(context).getString(KEY_TEAM_HOME_NAME, "") ?: ""

    fun getTeamAwayName(context: Context): String = prefs(context).getString(KEY_TEAM_AWAY_NAME, "") ?: ""

    fun saveTeamNames(context: Context, teamHomeName: String, teamAwayName: String) {
        prefs(context).edit()
            .putString(KEY_TEAM_HOME_NAME, teamHomeName)
            .putString(KEY_TEAM_AWAY_NAME, teamAwayName)
            .apply()
    }

    /** 燒入計分板左側的賽事名稱（例如「世界盃資格賽」），空字串代表不顯示該區塊。 */
    fun getEventName(context: Context): String = prefs(context).getString(KEY_EVENT_NAME, "") ?: ""

    fun saveEventName(context: Context, eventName: String) {
        prefs(context).edit().putString(KEY_EVENT_NAME, eventName).apply()
    }

    // ---------- v0.16.0：功能一——直播標題雙模板（見上方鍵定義／SettingsActivity） ----------

    /** 標題模板一：從未設定過時預設帶入現行既有的直播標題值（升級不破壞現況，見計畫書功能一）。 */
    fun getTitleTemplate1(context: Context): String =
        prefs(context).getString(KEY_TITLE_TEMPLATE_1, null) ?: getStreamTitle(context)

    /** 標題模板二：預設空字串。 */
    fun getTitleTemplate2(context: Context): String =
        prefs(context).getString(KEY_TITLE_TEMPLATE_2, "") ?: ""

    /** 目前選用的模板（1 或 2），預設 1。 */
    fun getSelectedTitleTemplate(context: Context): Int =
        prefs(context).getInt(KEY_TITLE_TEMPLATE_SELECTED, 1)

    fun saveTitleTemplates(context: Context, template1: String, template2: String, selected: Int) {
        prefs(context).edit()
            .putString(KEY_TITLE_TEMPLATE_1, template1)
            .putString(KEY_TITLE_TEMPLATE_2, template2)
            .putInt(KEY_TITLE_TEMPLATE_SELECTED, if (selected == 2) 2 else 1)
            .apply()
    }

    // ---------- v0.16.0：功能三——休息畫面四節計分表的各節已結算分數 ----------

    /** 主隊各節已結算分數（長度 [QUARTER_COUNT]，-1＝該節尚未結算）。 */
    fun getQuarterScoresHome(context: Context): IntArray =
        parseQuarterScores(prefs(context).getString(KEY_QUARTER_SCORES_HOME, null))

    fun getQuarterScoresAway(context: Context): IntArray =
        parseQuarterScores(prefs(context).getString(KEY_QUARTER_SCORES_AWAY, null))

    fun saveQuarterScores(context: Context, home: IntArray, away: IntArray) {
        prefs(context).edit()
            .putString(KEY_QUARTER_SCORES_HOME, home.joinToString(","))
            .putString(KEY_QUARTER_SCORES_AWAY, away.joinToString(","))
            .apply()
    }

    /** 開播勾「重置計分板」時一併清空各節結算分數（見 LiveActivity.resetScoreboardForNewGame）。 */
    fun clearQuarterScores(context: Context) {
        prefs(context).edit()
            .remove(KEY_QUARTER_SCORES_HOME)
            .remove(KEY_QUARTER_SCORES_AWAY)
            .apply()
    }

    /** 把「-1,18,22,15」這種逗號字串解析成長度 [QUARTER_COUNT] 的陣列（缺值／解析失敗一律補 -1）。 */
    private fun parseQuarterScores(raw: String?): IntArray {
        val result = IntArray(QUARTER_COUNT) { -1 }
        if (raw.isNullOrBlank()) return result
        raw.split(",").forEachIndexed { index, part ->
            if (index < QUARTER_COUNT) result[index] = part.trim().toIntOrNull() ?: -1
        }
        return result
    }

    // ---------- v0.10.0：同步錄影備份設定（見 LiveActivity/SettingsActivity 對應區塊） ----------

    /** 同步錄影備份開關，預設關（YAGNI：不影響現有使用者，需自行到設定頁開啟）。 */
    fun isRecordEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_RECORD_ENABLED, false)

    /** 錄影解析度：「與直播相同」共用編碼器，或獨立的 720p/1080p（畫質恆定，見類別頂端計畫書引用）。 */
    fun getRecordResolution(context: Context): String =
        prefs(context).getString(KEY_RECORD_RESOLUTION, RECORD_RESOLUTION_SAME_AS_LIVE) ?: RECORD_RESOLUTION_SAME_AS_LIVE

    /** 錄影存檔位置：「相簿」（MediaStore）或「自訂資料夾…」（SAF，見 [getRecordTreeUri]）。 */
    fun getRecordSaveMode(context: Context): String =
        prefs(context).getString(KEY_RECORD_SAVE_MODE, RECORD_SAVE_MODE_GALLERY) ?: RECORD_SAVE_MODE_GALLERY

    /** 自訂資料夾的 SAF 授權 Uri（字串形式）；選擇資料夾當下立即存檔，不等「儲存設定」按鈕。 */
    fun getRecordTreeUri(context: Context): String = prefs(context).getString(KEY_RECORD_TREE_URI, "") ?: ""

    fun saveRecordTreeUri(context: Context, treeUri: String) {
        prefs(context).edit().putString(KEY_RECORD_TREE_URI, treeUri).apply()
    }

    // ---------- v0.13.0：功能 A 精彩時刻標記——標記回推秒數 ----------

    /** 標記回推秒數字串（例如「10 秒」），與 strings.xml 的 highlight_rebound_seconds_options 一致。 */
    fun getHighlightReboundSeconds(context: Context): String =
        prefs(context).getString(KEY_HIGHLIGHT_REBOUND_SECONDS, DEFAULT_HIGHLIGHT_REBOUND_SECONDS)
            ?: DEFAULT_HIGHLIGHT_REBOUND_SECONDS

    // v0.15.1：孤兒直播回收（見 KEY_PENDING_BROADCAST_ID 註解）
    fun savePendingBroadcastId(context: Context, broadcastId: String) {
        prefs(context).edit().putString(KEY_PENDING_BROADCAST_ID, broadcastId).apply()
    }

    fun getPendingBroadcastId(context: Context): String? =
        prefs(context).getString(KEY_PENDING_BROADCAST_ID, null)?.takeIf { it.isNotBlank() }

    fun clearPendingBroadcastId(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_BROADCAST_ID).apply()
    }

    fun saveHighlightReboundSeconds(context: Context, value: String) {
        prefs(context).edit().putString(KEY_HIGHLIGHT_REBOUND_SECONDS, value).apply()
    }

    /** 解析「10 秒」成整數秒，解析失敗回傳預設 10。 */
    fun parseHighlightReboundSeconds(value: String): Int =
        Regex("""(\d+)""").find(value)?.value?.toIntOrNull() ?: 10
}
