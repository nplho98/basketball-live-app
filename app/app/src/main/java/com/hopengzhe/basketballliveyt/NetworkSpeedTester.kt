package com.hopengzhe.basketballliveyt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * 一鍵測速：實際上傳隨機產生的資料到公開測速端點，連續量測一段時間（時長由設定頁傳入，
 * v0.12.1 起為 10 秒）的真實上傳吞吐量，並依「不卡頓優先於畫質」原則換算出解析度／fps／
 * 碼率的建議組合（見 [recommendSpec]）。
 *
 * 測速方法與準確度限制（誠實聲明，非精確測量，見開發回報）：
 * - 端點使用 Cloudflare 公開的 `speed.cloudflare.com/__up`——這是 Cloudflare 官方測速網頁
 *   （speed.cloudflare.com）本身呼叫的同一個端點，經實測會回傳 HTTP 200 並接受任意大小的
 *   POST 二進位內容，屬穩定可靠的免費公開上傳端點；但並非本 APP 私有伺服器，長期可用性仍
 *   依賴 Cloudflare 服務本身。
 * - v0.8.20：改成 60 秒連續測速（見 [measureUploadCurve]），取代原本單次 4MB 量測——單次量測
 *   只反映「那一刻」的吞吐量，遇到現場網路忽好忽壞時容易誤判；連續 60 秒取樣能看出整段期間
 *   有沒有掉速，設定頁會即時畫出速率曲線（見 [NetworkSpeedChartView]）。
 * - v0.8.22：換算建議規格改成排除掉這 60 秒內最極端的兩個下探尖峰，取**剩餘取樣中最低的
 *   一次**（見 [SettingsActivity.runNetworkDetection]）——原本直接取全程最低值太敏感，單一次
 *   Wi-Fi 瞬間打嗝就會把整段其實很穩定的網路拉成過度保守的建議，排除極端值後更貼近「現場
 *   網路持續撐不住的真實下限」，而非「一瞬間的異常」。
 * - 每個小區塊仍是單一 TCP 連線、單次量測（非多執行緒平行上傳）：量測值可能略低於實際可用
 *   頻寬上限（尤其低延遲高頻寬的 Wi-Fi 環境，TCP slow start 可能還沒完全撐開傳輸視窗量測就
 *   結束）；但對本 APP「建議值刻意保守、寧可低估也不要高估」的用途而言，此低估傾向反而是
 *   可接受、甚至有利的方向。
 * - 行動網路訊號本身會波動，前後兩次測出的數字有落差是真實網路狀況變動，並非量測方法有誤。
 *
 * v0.12.2：修「測速顯示 0M／曲線前段空白／有時整條沒有」——根因是原本 512KB 一塊、傳完才有
 * 取樣點：慢網路第一塊耗時數秒，曲線前段自然空白；若第一塊超過量測時長（設定頁 10 秒），
 * 唯一的取樣點時間戳就落在圖表座標軸外，整條線都看不到；上傳失敗記 0，只有零星樣本時
 * 建議值可能算出 0M。修法：[CURVE_CHUNK_BYTES] 512KB → 128KB（取樣密度×4）；[measureUploadCurve]
 * 開測前先傳一個 [WARMUP_CHUNK_BYTES] 暖身塊、結果不計入量測（TLS 握手／TCP slow start
 * 不拖慢第一個正式取樣塊）；取樣點時間戳超出量測時長就不畫進圖表（見
 * [SettingsActivity.runNetworkDetection]，數值仍計入建議規格判斷）；有效（非 0）樣本為 0 個
 * 時視為測速失敗，不再拿失敗記錄的 0 去算出誤導性的 0M 建議（同樣見 SettingsActivity）。
 */
object NetworkSpeedTester {

    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    // v0.12.2：512KB → 128KB，取樣密度×4，慢網路也能更快出現第一個取樣點
    private const val CURVE_CHUNK_BYTES = 128 * 1024
    // v0.12.2：暖身塊——量測正式開始前先傳一塊小的，讓 TLS 握手／TCP slow start 消耗掉，
    // 結果直接丟棄不計入任何取樣，避免它拖慢/拖歪第一個正式取樣點的耗時
    private const val WARMUP_CHUNK_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    /**
     * 實測上傳速度（單位 Mbps）。在 [Dispatchers.IO] 執行緒實際傳送 [sizeBytes] 位元組的
     * 隨機資料，只計算「實際寫出資料＋等待伺服器回應」這段時間，換算成 Mbps。
     * 連線失敗／伺服器非 2xx／逾時一律拋出例外，由呼叫端 catch 後處理。
     */
    private suspend fun uploadChunkAndMeasureMbps(sizeBytes: Int): Double = withContext(Dispatchers.IO) {
        val payload = ByteArray(sizeBytes).also { Random.nextBytes(it) }
        val connection = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(payload.size)

            val startTime = System.nanoTime()
            connection.outputStream.use { it.write(payload) }
            val responseCode = connection.responseCode // 觸發實際等待伺服器回應，確保資料真的送達
            val elapsedSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0

            if (responseCode !in 200..299) {
                throw IOException("測速端點回應異常：HTTP $responseCode")
            }
            if (elapsedSeconds <= 0.0) {
                throw IOException("測速耗時異常，無法計算速度")
            }

            val bits = payload.size * 8.0
            (bits / elapsedSeconds) / 1_000_000.0
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 連續測速：重複上傳固定大小區塊直到累積耗時達到 [durationMs]（設定頁按鈕傳入，v0.12.1 起 10 秒），
     * 每完成一個區塊就透過 [onSample] 回呼「距離開始經過的毫秒數」與該區塊的即時 Mbps，
     * 供畫面即時畫出速率曲線（見 [NetworkSpeedChartView]）。
     * 區塊大小用較小的 [CURVE_CHUNK_BYTES]（v0.12.2：128KB），連線快時單一區塊很快傳完、
     * 取樣點自然變多；連線慢時區塊也不會大到讓單次上傳耗時過久。呼叫前會先傳一個
     * [WARMUP_CHUNK_BYTES] 暖身塊（結果不計入任何取樣，見本函式內開頭）。
     * 單一區塊上傳失敗（連線中斷等）不中止整個量測流程，改記錄為 0 Mbps 繼續累計，
     * 因為那正是「這段時間網路實際上傳不出去」的真實狀況，直接排除掉反而失真。
     * 回傳完整取樣序列，呼叫端自行決定用最低值／平均值等方式換算建議規格；v0.12.2 起呼叫端
     * （[SettingsActivity.runNetworkDetection]）改為只用非 0 的有效樣本換算，並在時間戳超出
     * [durationMs] 時略過不畫進曲線。
     */
    suspend fun measureUploadCurve(
        durationMs: Long,
        onSample: suspend (elapsedMs: Long, mbps: Double) -> Unit
    ): List<Double> {
        // v0.12.2：暖身塊——結果直接丟棄，不計入取樣、不觸發 onSample，純粹讓連線熱身。
        // 暖身失敗（連線一開始就不通）不特別處理，讓後面的正式迴圈自然決定成敗即可。
        try {
            uploadChunkAndMeasureMbps(WARMUP_CHUNK_BYTES)
        } catch (e: IOException) {
            // 忽略：暖身本來就允許失敗
        }

        val samples = mutableListOf<Double>()
        val startTime = System.currentTimeMillis()
        val deadline = startTime + durationMs
        while (System.currentTimeMillis() < deadline) {
            val mbps = try {
                uploadChunkAndMeasureMbps(CURVE_CHUNK_BYTES)
            } catch (e: IOException) {
                0.0
            }
            samples.add(mbps)
            onSample(System.currentTimeMillis() - startTime, mbps)
        }
        return samples
    }

    /** resolution／fps／bitrate 需與 strings.xml 內對應 string-array 的項目文字完全一致，才能設回 Spinner。 */
    data class Recommendation(val resolution: String, val fps: String, val bitrate: String)

    /**
     * 速度對應規格對照表：以「不卡頓優先於畫質」為原則，往下抓保守值。
     * fps 固定建議 30——24fps 對本 APP 用途過度保守，60fps 需要明顯更高碼率才不失真，
     * 兩者都會讓「碼率是否夠用」的判斷多一個變數；30fps 是兼顧流暢度與資料量的折衷選擇，
     * 套用建議後使用者仍可自行手動改回 24/60fps（不會被鎖定）。
     */
    fun recommendSpec(uploadMbps: Double): Recommendation = when {
        uploadMbps < 1.5 -> Recommendation("854x480（480p）", "30 fps", "1400 Kbps")
        uploadMbps < 3.0 -> Recommendation("1280x720（720p）", "30 fps", "2300 Kbps")
        uploadMbps < 4.5 -> Recommendation("1280x720（720p）", "30 fps", "3300 Kbps")
        uploadMbps < 6.5 -> Recommendation("1920x1080（1080p）", "30 fps", "3300 Kbps")
        else -> Recommendation("1920x1080（1080p）", "30 fps", "4000 Kbps")
    }
}
