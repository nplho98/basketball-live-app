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
    // v0.18.38：YouTube 兩組帳號——A 組金鑰沿用 KEY_STREAM_KEY（舊設定不用搬）
    private const val KEY_YT_PROFILE = "yt_profile"
    private const val KEY_YT_LABEL_A = "yt_label_a"
    private const val KEY_YT_LABEL_B = "yt_label_b"
    private const val KEY_YT_KEY_B = "yt_key_b"
    private const val KEY_KEYS_LOCKED = "keys_locked"
    private const val KEY_YT_ACCOUNT_A = "yt_account_a"
    private const val KEY_YT_ACCOUNT_B = "yt_account_b"

    private const val KEY_STREAM_KEY_FACEBOOK = "stream_key_facebook"
    private const val KEY_FACEBOOK_SERVER_URL = "facebook_server_url"
    private const val KEY_STREAM_KEY_CUSTOM = "stream_key_custom"
    // v0.18.16：直播平台——YouTube 走帳號模式自動建立直播；Facebook／自訂只用推流網址（見
    // LiveActivity.resolveCustomRtmpUrl）。選項文字需與 strings.xml 的 live_platform_options 一致。
    private const val KEY_LIVE_PLATFORM = "live_platform"
    const val PLATFORM_YOUTUBE = "YouTube（帳號模式）"
    const val PLATFORM_FACEBOOK = "Facebook（網址＋金鑰）"
    const val PLATFORM_CUSTOM = "自訂推流網址"

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

    // v0.22.1：關掉 APP 再開要接回上一場（Boss 2026-09-06 拍板「一律接回」）。
    // 檔名時間戳存起來，重開才找得到同一份 highlights_/stats_ 檔；
    // 計分板現值也一起存，否則接回統計卻是 0-0，收播圖會自相矛盾。
    // 舊資料最多活到下次開播那一刻——開播一律重置計分板並換新時間戳。
    private const val KEY_LAST_SESSION_STAMP = "last_session_stamp"
    private const val KEY_SCORE_HOME = "score_home"
    private const val KEY_SCORE_AWAY = "score_away"
    private const val KEY_FOUL_HOME = "foul_home"
    private const val KEY_FOUL_AWAY = "foul_away"
    private const val KEY_PERIOD = "period"
    const val QUARTER_COUNT = 4

    /** v0.18.15：各節分數欄位總格數＝正規四節＋三次延長（OT1～OT3），節數上限也是這個數。 */
    const val PERIOD_SLOT_COUNT = QUARTER_COUNT + 3

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
    const val DEFAULT_BITRATE = "3300 Kbps"
    const val LOW_LATENCY_BITRATE = "3300 Kbps"

    private val ALLOWED_RESOLUTIONS = setOf("1920x1080（1080p）", DEFAULT_RESOLUTION)
    private val ALLOWED_FPS_VALUES = setOf("24 fps", DEFAULT_FPS)
    private val ALLOWED_BITRATES = setOf(DEFAULT_BITRATE, "4000 Kbps")

    // v0.10.0：同步錄影備份選項文字，需與 strings.xml 的 record_resolution_options／
    // record_save_mode_options 完全一致（Spinner 還原機制，沿用上面既有慣例）
    const val RECORD_RESOLUTION_SAME_AS_LIVE = "與直播相同"
    const val RECORD_RESOLUTION_FIXED_1080P30 = "1920x1080（1080p／30 fps／20 Mbps）"
    const val RECORD_SAVE_MODE_GALLERY = "相簿"
    const val RECORD_SAVE_MODE_DOWNLOADS = "Downloads"
    const val RECORD_SAVE_MODE_CUSTOM_FOLDER = "自訂資料夾…"

    // v0.15.5：已移除功能殘留的死鍵——+3 特效（v0.14.1 拆）與公牛動畫開關（v0.11.x），
    // 現存程式碼已無任何讀寫，只剩舊裝置 SharedPreferences 內的殘值，啟動時清掉
    // v0.15.6：VBR 開關移除（實質只影響碼率顯示平滑，名不副實，Boss 拍板移除），鍵一併清掉
    // v0.18.13：720p 錄影實驗移除（A/B 比較已完成，正式錄影只保留 1080p30/20Mbps），鍵一併清掉
    private val DEPRECATED_KEYS =
        listOf("home_plus3_celebration", "bull_anim_enabled", "vbr", "record_720p_experiment")

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

    fun getResolution(context: Context): String {
        val preferences = prefs(context)
        val saved = preferences.getString(KEY_RESOLUTION, DEFAULT_RESOLUTION)
        val migrated = coerceResolution(saved)
        if (saved != migrated) preferences.edit().putString(KEY_RESOLUTION, migrated).apply()
        return migrated
    }

    fun getFps(context: Context): String {
        val preferences = prefs(context)
        val saved = preferences.getString(KEY_FPS, DEFAULT_FPS)
        val migrated = coerceFps(saved)
        if (saved != migrated) preferences.edit().putString(KEY_FPS, migrated).apply()
        return migrated
    }

    /** 將已移除或無效的直播解析度回落到預設值；供讀取舊設定時遷移。 */
    fun coerceResolution(value: String?): String =
        value?.takeIf { it in ALLOWED_RESOLUTIONS } ?: DEFAULT_RESOLUTION

    /** 將已移除或無效的直播影格率回落到預設值；供讀取舊設定時遷移。 */
    fun coerceFps(value: String?): String =
        value?.takeIf { it in ALLOWED_FPS_VALUES } ?: DEFAULT_FPS

    fun getBitrate(context: Context): String {
        val preferences = prefs(context)
        val saved = preferences.getString(KEY_BITRATE, DEFAULT_BITRATE)
        val migrated = coerceBitrate(saved)
        if (saved != migrated) preferences.edit().putString(KEY_BITRATE, migrated).apply()
        return migrated
    }

    /** 將已移除或無效的直播碼率回落到預設值；供讀取舊設定時遷移。 */
    fun coerceBitrate(value: String?): String =
        value?.takeIf { it in ALLOWED_BITRATES } ?: DEFAULT_BITRATE

    /** 直播中碼率自動調整開關：true＝網路壅塞時自動降碼率（見 LiveActivity.onNewBitrate），
     *  false＝全程固定用使用者選的碼率，不受 BitrateAdapter 影響。 */
    fun isBitrateAutoAdjust(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BITRATE_AUTO_ADJUST, true)

    /** 開發測試用的 YouTube 串流金鑰，未來會改由帳號登入自動取得。 */
    fun getStreamKey(context: Context): String = prefs(context).getString(KEY_STREAM_KEY, "") ?: ""

    // ---------- v0.18.38：YouTube 兩組帳號設定（Boss 指定：選 A 就整組帶 A 的金鑰） ----------

    /** 目前選用的組別：1＝帳號 A、2＝帳號 B。 */
    fun getYouTubeProfile(context: Context): Int = prefs(context).getInt(KEY_YT_PROFILE, 1)

    /** v0.18.40：金鑰欄位是否鎖定（預設鎖住，要改先解鎖）。 */
    fun isKeyFieldsLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_KEYS_LOCKED, true)

    fun saveKeyFieldsLocked(context: Context, locked: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYS_LOCKED, locked).apply()
    }

    /** 組別名稱（自己取，例如「校隊頻道」），空字串時由 UI 顯示預設字樣。 */
    fun getYouTubeLabel(context: Context, profile: Int): String =
        prefs(context).getString(if (profile == 2) KEY_YT_LABEL_B else KEY_YT_LABEL_A, "") ?: ""

    /** 該組的串流金鑰；A 組沿用舊的 [KEY_STREAM_KEY]，舊設定直接相容不用重貼。 */
    fun getYouTubeKey(context: Context, profile: Int): String =
        if (profile == 2) prefs(context).getString(KEY_YT_KEY_B, "") ?: "" else getStreamKey(context)

    /** 該組上次實際登入直播用的 Google 帳號（開播成功時自動記下，供切組時比對提醒）。 */
    fun getYouTubeAccount(context: Context, profile: Int): String =
        prefs(context).getString(if (profile == 2) KEY_YT_ACCOUNT_B else KEY_YT_ACCOUNT_A, "") ?: ""

    fun saveYouTubeAccount(context: Context, profile: Int, email: String) {
        prefs(context).edit()
            .putString(if (profile == 2) KEY_YT_ACCOUNT_B else KEY_YT_ACCOUNT_A, email)
            .apply()
    }

    fun saveYouTubeProfiles(context: Context, profile: Int, labelA: String, labelB: String, keyB: String) {
        prefs(context).edit()
            .putInt(KEY_YT_PROFILE, profile)
            .putString(KEY_YT_LABEL_A, labelA)
            .putString(KEY_YT_LABEL_B, labelB)
            .putString(KEY_YT_KEY_B, keyB)
            .apply()
    }

    /**
     * v0.18.17：三個平台各存各的金鑰，切平台不用重貼（Boss 指定）。
     * [KEY_STREAM_KEY] 沿用成 YouTube 那格（舊設定直接相容）。
     */
    fun getFacebookStreamKey(context: Context): String =
        prefs(context).getString(KEY_STREAM_KEY_FACEBOOK, "") ?: ""

    fun getCustomStreamUrl(context: Context): String =
        prefs(context).getString(KEY_STREAM_KEY_CUSTOM, "") ?: ""

    /**
     * v0.18.21：FB 伺服器網址一律由使用者自己填，**不給預設值**（Boss 指定：避免拿到 APK 的人
     * 沿用別人帶進來的網址）。留空＝不能開播，開播時擋下並提示（見 LiveActivity.startLiveStream）。
     */
    fun getFacebookServerUrl(context: Context): String =
        (prefs(context).getString(KEY_FACEBOOK_SERVER_URL, "") ?: "").trim()

    fun savePlatformStreamKeys(context: Context, facebookKey: String, facebookServerUrl: String, customUrl: String) {
        prefs(context).edit()
            .putString(KEY_STREAM_KEY_FACEBOOK, facebookKey)
            .putString(KEY_FACEBOOK_SERVER_URL, facebookServerUrl)
            .putString(KEY_STREAM_KEY_CUSTOM, customUrl)
            .apply()
    }

    /** 只認關鍵字，選項文字改版（例 v0.18.23「貼金鑰」→「網址＋金鑰」）也不會讓舊設定失效。 */
    fun getLivePlatform(context: Context): String {
        val saved = prefs(context).getString(KEY_LIVE_PLATFORM, PLATFORM_YOUTUBE) ?: PLATFORM_YOUTUBE
        return when {
            saved.contains("Facebook", ignoreCase = true) -> PLATFORM_FACEBOOK
            saved.contains("自訂") -> PLATFORM_CUSTOM
            else -> PLATFORM_YOUTUBE
        }
    }

    fun saveLivePlatform(context: Context, platform: String) {
        prefs(context).edit().putString(KEY_LIVE_PLATFORM, platform).apply()
    }

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

    /** 解析碼率字串（例如「3300 Kbps」）成 bps，解析失敗則回傳預設碼率。 */
    fun parseBitrate(bitrate: String): Int {
        val defaultKbps = Regex("""(\d+)""").find(DEFAULT_BITRATE)!!.value.toInt()
        val kbps = Regex("""(\d+)""").find(bitrate)?.value?.toIntOrNull() ?: defaultKbps
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

    /**
     * v0.22.1：上一場的檔名時間戳。null＝從來沒開播過，重開 APP 就是全新一場。
     * 只在開播那一刻寫入（[LiveActivity.beginRtmpStreaming]），收播不清——收播後還要輸出圖片／傳 LINE。
     */
    fun getLastSessionStamp(context: Context): String? =
        prefs(context).getString(KEY_LAST_SESSION_STAMP, null)

    fun saveLastSessionStamp(context: Context, stamp: String) {
        prefs(context).edit().putString(KEY_LAST_SESSION_STAMP, stamp).apply()
    }

    /** v0.22.1：計分板現值（分數／犯規／節數）。各節結算分數走 [getQuarterScoresHome] 那組。 */
    data class ScoreboardState(
        val scoreHome: Int,
        val scoreAway: Int,
        val foulHome: Int,
        val foulAway: Int,
        val period: Int
    )

    fun getScoreboardState(context: Context): ScoreboardState = prefs(context).let { p ->
        ScoreboardState(
            scoreHome = p.getInt(KEY_SCORE_HOME, 0),
            scoreAway = p.getInt(KEY_SCORE_AWAY, 0),
            foulHome = p.getInt(KEY_FOUL_HOME, 0),
            foulAway = p.getInt(KEY_FOUL_AWAY, 0),
            // 節數下限夾 1：舊版偏好沒有這個 key，讀到 0 會讓計分板顯示「第 0 節」
            period = p.getInt(KEY_PERIOD, 1).coerceAtLeast(1)
        )
    }

    fun saveScoreboardState(context: Context, state: ScoreboardState) {
        prefs(context).edit()
            .putInt(KEY_SCORE_HOME, state.scoreHome)
            .putInt(KEY_SCORE_AWAY, state.scoreAway)
            .putInt(KEY_FOUL_HOME, state.foulHome)
            .putInt(KEY_FOUL_AWAY, state.foulAway)
            .putInt(KEY_PERIOD, state.period)
            .apply()
    }

    /** 開播勾「重置計分板」時一併清空各節結算分數（見 LiveActivity.resetScoreboardForNewGame）。 */
    fun clearQuarterScores(context: Context) {
        prefs(context).edit()
            .remove(KEY_QUARTER_SCORES_HOME)
            .remove(KEY_QUARTER_SCORES_AWAY)
            .apply()
    }

    /**
     * 把「-1,18,22,15」這種逗號字串解析成長度 [PERIOD_SLOT_COUNT] 的陣列（缺值／解析失敗一律補 -1）。
     * v0.18.15 前存的是 4 格舊字串，後面 3 格延長賽自動補 -1，不用特別轉檔。
     */
    private fun parseQuarterScores(raw: String?): IntArray {
        val result = IntArray(PERIOD_SLOT_COUNT) { -1 }
        if (raw.isNullOrBlank()) return result
        raw.split(",").forEachIndexed { index, part ->
            if (index < PERIOD_SLOT_COUNT) result[index] = part.trim().toIntOrNull() ?: -1
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

    // ---------- v0.19.0：球員名單（三個年級各一份，只存姓名） ----------
    // ponytail: 36 個姓名直接存 SharedPreferences 的換行字串，不開資料庫、不留固定 12 格空位；
    // 名單規模變大或要記背號／個人數據時再換結構。

    private const val KEY_ROSTER_PREFIX = "roster_"
    private const val KEY_ROSTER_ACTIVE_GRADE = "roster_active_grade"

    /** 每個年級的名單上限；超出的行在存檔時就被截掉。 */
    // v0.22.0：12 → 15（Boss 2026-09-06）。選人視窗同步改 5 欄，15÷5＝三列剛好排滿。
    const val ROSTER_MAX_SIZE = 15

    /** 年級選項，需與 strings.xml 的 roster_grade_options 一致。 */
    val ROSTER_GRADES = listOf("七年級", "八年級", "九年級")

    val DEFAULT_ROSTER_GRADE: String = ROSTER_GRADES.first()

    /**
     * 多行文字整成名單：正規化 CRLF／CR、去頭尾空白、丟掉空行，最多 [ROSTER_MAX_SIZE] 位。
     * 存檔與讀取都走這裡，格式契約只有一份。
     */
    /**
     * v0.22.11：名單一位球員一筆，含背號（Boss 2026-09-06）。
     *
     * 存檔格式仍是「一行一位」，每行改成 `號碼|姓名`。**沒有分隔符號的舊資料整行當姓名、
     * 號碼留空**——v0.22.10 以前存的名單不用轉檔就能繼續用。
     */
    data class RosterEntry(val number: String, val name: String)

    private const val ROSTER_FIELD_SEP = "|"

    fun parseRosterEntries(raw: String?): List<RosterEntry> = (raw ?: "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .mapNotNull { line ->
            val parts = line.split(ROSTER_FIELD_SEP, limit = 2)
            val entry =
                if (parts.size == 2) RosterEntry(parts[0].trim(), parts[1].trim())
                else RosterEntry("", parts[0].trim())
            // 姓名空白＝這一格沒人，整筆丟掉（號碼單獨存在沒有意義，也不能拿來記數據）
            entry.takeIf { it.name.isNotEmpty() }
        }
        .take(ROSTER_MAX_SIZE)

    /** 姓名清單。統計、選人視窗、分享文字都只認姓名，維持既有型別不動。 */
    fun parseRoster(raw: String?): List<String> = parseRosterEntries(raw).map { it.name }

    fun serializeRosterEntries(entries: List<RosterEntry>): String =
        entries.joinToString("\n") { it.number + ROSTER_FIELD_SEP + it.name }

    fun getRosterEntries(context: Context, grade: String): List<RosterEntry> =
        parseRosterEntries(prefs(context).getString(KEY_ROSTER_PREFIX + grade, ""))

    fun getRoster(context: Context, grade: String): List<String> =
        getRosterEntries(context, grade).map { it.name }

    /** 名單編輯按確定／儲存當下就存檔（不等「儲存設定」），避免切年級時草稿遺失。 */
    fun saveRosterEntries(context: Context, grade: String, entries: List<RosterEntry>) {
        val cleaned = entries
            .map { RosterEntry(it.number.trim(), it.name.trim()) }
            .filter { it.name.isNotEmpty() }
            .take(ROSTER_MAX_SIZE)
        prefs(context).edit()
            .putString(KEY_ROSTER_PREFIX + grade, serializeRosterEntries(cleaned))
            .apply()
    }

    /** 本場套用的年級；存到非法值（改版刪過的年級）時退回預設。 */
    fun getActiveRosterGrade(context: Context): String =
        prefs(context).getString(KEY_ROSTER_ACTIVE_GRADE, DEFAULT_ROSTER_GRADE)
            ?.takeIf { it in ROSTER_GRADES } ?: DEFAULT_ROSTER_GRADE

    fun saveActiveRosterGrade(context: Context, grade: String) {
        prefs(context).edit().putString(KEY_ROSTER_ACTIVE_GRADE, grade).apply()
    }
}
