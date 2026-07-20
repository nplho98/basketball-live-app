package com.hopengzhe.basketballliveyt

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * v0.17.0（第一階段第3項）：診斷時間軸記錄器。把變焦按鍵/連線 callback/重連/網路事件/碼率
 * (raw、adapter 目標、hasCongestion、實際 setVideoBitrateOnFly) 逐筆帶毫秒時戳寫進 APP 外部檔案區
 * `diag/diag_yyyyMMdd.txt`，供 adb pull 回來逐筆核對。
 *
 * v0.17.1（第一階段審查第1項）：改為**非同步單一寫入者**——呼叫端（主執行緒、音量鍵、連線/碼率
 * 回呼）只把「已格式化好的一行」丟進有上限的佇列即返回，不碰磁碟；真正 mkdirs/appendText 由一條
 * 背景 daemon 執行緒序列化執行。佇列滿時直接丟棄新訊息（高頻 BITRATE 掉幾筆無妨），**絕不阻塞直播**。
 * 時戳在呼叫端當下取得，故即使寫入落後，行序仍反映事件發生順序。寫檔失敗只記一筆 Logcat。
 *
 * ponytail: 一天一檔、無輪替、佇列滿即丟（不做合併壓縮）。診斷檔量小、使用者手動 adb pull 後自行清理。
 */
object DiagLogger {

    private const val TAG = "DiagLogger"
    private const val DIR_NAME = "diag"
    private const val QUEUE_CAPACITY = 512

    // SimpleDateFormat 非執行緒安全，且時戳要在各呼叫端執行緒取得，用 ThreadLocal 各持一份
    private val lineTimeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.TAIWAN) }
    private val fileDateFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd", Locale.TAIWAN) }

    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)

    @Volatile private var appContext: Context? = null
    // 測試用覆寫：設定後所有寫入導向此檔，跳過 Android Context（見 DiagLoggerTest）
    @Volatile private var testFileOverride: File? = null
    @Volatile private var writerStarted = false

    /** 一段直播/一次開播的分隔線，方便在時間軸裡快速定位每一場。 */
    fun startSession(context: Context, label: String) {
        log(context, "SESSION", "========== $label ==========")
    }

    /**
     * 附毫秒時戳排入佇列即返回（不等磁碟）。`tag` 是事件類別（ZOOM/CONN/RECONNECT/NET/BITRATE…），
     * `message` 是內容。佇列滿時丟棄本筆，不阻塞呼叫端。
     */
    fun log(context: Context, tag: String, message: String) {
        appContext = context.applicationContext
        ensureWriter()
        // offer：非阻塞，佇列滿回 false 直接丟棄（不讓直播/UI 等磁碟）
        queue.offer(formatLine(tag, message))
    }

    /** 格式化一行（純函式、可測）。行內不含換行，寫入時才補 `\n`，故並行排入不會撕裂彼此的行。 */
    fun formatLine(tag: String, message: String): String =
        "${lineTimeFmt.get().format(Date())} [$tag] $message"

    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            thread(isDaemon = true, name = "DiagLogger-writer") {
                while (true) {
                    val line = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        continue
                    }
                    writeLine(line)
                }
            }
        }
    }

    /** 真正落檔（單一執行緒序列化呼叫，無並行競爭）。寫檔失敗只記 Logcat，不拋出。 */
    private fun writeLine(line: String) {
        try {
            resolveTargetFile()?.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "寫入診斷記錄失敗", e)
        }
    }

    private fun resolveTargetFile(): File? {
        testFileOverride?.let { return it }
        val ctx = appContext ?: return null
        val dir = File(ctx.getExternalFilesDir(null), DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "diag_${fileDateFmt.get().format(Date())}.txt")
    }

    // ---------- 測試專用（見 DiagLoggerTest）：不需 Android Context 即可驗證格式化與並行排入不撕裂行 ----------

    /** 測試：把所有寫入導向指定檔案，並停用背景 daemon（改由 [drainForTest] 在呼叫端同步落檔）。 */
    fun configureForTest(file: File) {
        testFileOverride = file
        writerStarted = true // 佔位避免測試中意外啟動 daemon 與 drain 競爭同一佇列
    }

    /** 測試：把當前佇列已排入的行，在呼叫端執行緒序列化寫出（與 production 單寫入者同一 writeLine 路徑）。 */
    fun drainForTest() {
        while (true) {
            val line = queue.poll() ?: break
            writeLine(line)
        }
    }

    /** 測試：不需 Context 直接排入（供並行測試呼叫）。 */
    fun enqueueForTest(tag: String, message: String) {
        queue.offer(formatLine(tag, message))
    }

    /** 測試：重置靜態狀態，避免測試互相污染。 */
    fun resetForTest() {
        queue.clear()
        testFileOverride = null
        appContext = null
        writerStarted = false
    }
}
