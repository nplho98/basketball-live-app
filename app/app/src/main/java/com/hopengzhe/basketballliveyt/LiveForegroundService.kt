package com.hopengzhe.basketballliveyt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * v0.12.0：直播前景服務——切背景／切其他 APP／久放不被系統殺進程斷播；來電時系統也不會
 * 把已在使用中的麥克風判給電話（前景服務對麥克風的優先權高於一般背景 APP）。
 *
 * 本身不持有任何相機／推流物件，相機與 RootEncoder 的生命週期完全由 [LiveActivity] 管理，
 * 這裡只負責掛一個常駐通知＋宣告 `camera|microphone` 前景服務類型，讓系統認可「這個 APP
 * 目前合法在用相機/麥克風」，Android 9+ 背景相機限制、Android 11+ 前景服務類型規定都靠這個
 * 服務滿足。啟動／更新通知文字／停止皆由 [LiveActivity] 呼叫（見 onConnectionSuccess／
 * [LiveActivity.scheduleReconnect]／stopLiveStream）；Android 11+ 規定前景服務必須在 APP
 * 仍在前景時啟動，之後才允許在背景繼續使用 camera/microphone，因此固定掛在「開播成功」這個
 * 還在前景的時間點啟動，不等使用者切背景才啟動。
 */
class LiveForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
            ?: getString(R.string.foreground_service_status_live)
        try {
            startForegroundWithNotification(statusText)
        } catch (e: Exception) {
            // 極少數系統限制（例如背景啟動前景服務被拒）不能讓這個服務拖垮直播本身，
            // 相機/推流生命週期完全由 LiveActivity 自己管理，這裡失敗只代表少了系統認可的
            // 前景服務身分（背景久放可能被殺進程），不影響當下正在進行的直播。
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * 同一個通知 ID 重複呼叫 startForeground 只會更新文字內容（不會疊出多筆通知），
     * 因此重連中／恢復直播時直接再呼叫一次即可即時換字，不需要額外的「更新通知」API。
     */
    private fun startForegroundWithNotification(statusText: String) {
        createNotificationChannelIfNeeded()
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** IMPORTANCE_LOW：沒有音效/震動，避免每次文字更新（重連中↔直播中）都打擾操作者。 */
    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.foreground_service_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "live_foreground_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        /**
         * 未授予 POST_NOTIFICATIONS 時通知不會顯示，但服務仍會正常啟動並取得前景服務身分。
         * 呼叫端（LiveActivity）不用另外包 try-catch——啟動服務失敗只代表少了系統認可的
         * 前景服務身分，絕不能連帶讓呼叫端的開播/重連流程跟著中斷。
         */
        fun start(context: Context, statusText: String) {
            try {
                val intent = Intent(context, LiveForegroundService::class.java).apply {
                    putExtra(EXTRA_STATUS_TEXT, statusText)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 見上方函式註解：失敗不影響直播本身
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LiveForegroundService::class.java))
            } catch (e: Exception) {
                // 服務本來就沒啟動或系統例外，忽略即可，不影響收播流程
            }
        }
    }
}
