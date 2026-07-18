package com.hopengzhe.basketballliveyt

import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.CdnSettings
import com.google.api.services.youtube.model.LiveBroadcast
import com.google.api.services.youtube.model.LiveBroadcastContentDetails
import com.google.api.services.youtube.model.LiveBroadcastSnippet
import com.google.api.services.youtube.model.LiveBroadcastStatus
import com.google.api.services.youtube.model.LiveStream
import com.google.api.services.youtube.model.LiveStreamSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 建立直播成功後的結果：推流用 RTMP 位址／金鑰，以及觀眾端分享連結。 */
data class LiveStreamCreationResult(
    val broadcastId: String,
    val ingestionAddress: String,
    val streamName: String,
    val watchUrl: String
) {
    /** RootEncoder 的 startStream() 需要完整 RTMP URL（推流位址 + 串流金鑰）。 */
    val fullRtmpUrl: String get() = "$ingestionAddress/$streamName"
}

/**
 * 封裝 YouTube Data API 建立直播的三段流程：
 * liveBroadcasts.insert（建直播事件）→ liveStreams.insert（建推流位址/金鑰）→ liveBroadcasts.bind（綁定兩者）。
 *
 * enableAutoStart 設為 true：推流一到 YouTube 自動開播。
 * v0.12.0：enableAutoStop 改 false（原本 true）——體育館網路閃斷、重連期間 RTMP 連線會短暫中斷，
 * 若沿用 auto-stop，YouTube 端可能在還在重連的當下就把該場直播判定結束，即使 APP 之後重連成功
 * 也無法復原（YouTube 直播一旦 complete 就無法恢復）。改為收播時才由 APP 主動呼叫
 * [endLiveBroadcast]（`liveBroadcasts.transition(complete)`）正式結束，見 LiveActivity.stopLiveStream。
 */
object YouTubeLiveRepository {

    suspend fun createLiveBroadcastAndStream(
        youtube: YouTube,
        title: String,
        privacyStatus: String,
        resolution: String,
        fps: Int
    ): LiveStreamCreationResult = withContext(Dispatchers.IO) {
        val broadcast = LiveBroadcast().apply {
            kind = "youtube#liveBroadcast"
            snippet = LiveBroadcastSnippet().apply {
                this.title = title
                scheduledStartTime = DateTime(System.currentTimeMillis())
            }
            status = LiveBroadcastStatus().apply {
                this.privacyStatus = privacyStatus
            }
            contentDetails = LiveBroadcastContentDetails().apply {
                enableAutoStart = true
                // v0.12.0：改 false，理由見類別頂端 KDoc——收播由 APP 呼叫 endLiveBroadcast 主動結束
                enableAutoStop = false
                // v0.7.0：明確指定「一般延遲」，取得 YouTube 伺服器端緩衝保護吸收短期網路波動
                // （查證官方文件 https://developers.google.com/youtube/v3/live/docs/liveBroadcasts，
                // 合法值僅 "normal"／"low"／"ultraLow" 三種；本 APP 無低延遲互動需求，一律採 normal，
                // 不需要在 APP 內自建本地緩衝／延遲上傳機制）
                latencyPreference = "normal"
            }
        }
        val insertedBroadcast = youtube.liveBroadcasts()
            .insert(listOf("snippet", "status", "contentDetails"), broadcast)
            .execute()

        val stream = LiveStream().apply {
            kind = "youtube#liveStream"
            snippet = LiveStreamSnippet().apply {
                this.title = title
            }
            cdn = CdnSettings().apply {
                ingestionType = "rtmp"
                this.frameRate = mapFpsToCdnLabel(fps)
                this.resolution = mapResolutionToCdnLabel(resolution)
            }
        }
        val insertedStream = youtube.liveStreams()
            .insert(listOf("snippet", "cdn"), stream)
            .execute()

        youtube.liveBroadcasts()
            .bind(insertedBroadcast.id, listOf("id", "contentDetails"))
            .setStreamId(insertedStream.id)
            .execute()

        val ingestionInfo = insertedStream.cdn.ingestionInfo
        LiveStreamCreationResult(
            broadcastId = insertedBroadcast.id,
            ingestionAddress = ingestionInfo.ingestionAddress,
            streamName = ingestionInfo.streamName,
            watchUrl = "https://youtu.be/${insertedBroadcast.id}"
        )
    }

    /** YouTube CDN resolution 標籤與本 APP 三種解析度對照；其餘一律歸類 480p。 */
    private fun mapResolutionToCdnLabel(resolution: String): String = when {
        resolution.contains("1920") -> "1080p"
        resolution.contains("1280") -> "720p"
        else -> "480p"
    }

    /** YouTube CDN frameRate 標籤僅接受 "30fps" 或 "60fps"；24fps 就近對應 30fps。 */
    private fun mapFpsToCdnLabel(fps: Int): String = if (fps >= 50) "60fps" else "30fps"

    /**
     * v0.12.0：收播時正式結束 YouTube 端直播（見類別頂端 KDoc）。broadcastStatus 傳
     * "complete"（合法值：invalid/testing/live/complete，這裡只會用到 complete）。
     * 呼叫失敗（配額用盡／網路異常／直播已被其他管道結束等）例外不在此吞掉，
     * 交由呼叫端 [com.hopengzhe.basketballliveyt.LiveActivity] 顯示「請到 YouTube 工作室
     * 手動結束直播」的明確錯誤訊息，不靜默。
     */
    suspend fun endLiveBroadcast(youtube: YouTube, broadcastId: String): Unit = withContext(Dispatchers.IO) {
        youtube.liveBroadcasts()
            .transition("complete", broadcastId, listOf("id", "status"))
            .execute()
        Unit
    }
}
