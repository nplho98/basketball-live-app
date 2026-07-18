package com.hopengzhe.basketballliveyt

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * v0.12.2：全域當機記錄（bug 2「直播中 APP 當掉無記錄」的觀測建設，見 [LiveActivity] 類別頂端
 * KDoc v0.12.2 段落）。
 *
 * [install] 在 [BasketballLiveApplication.onCreate] 盡早呼叫，掛上
 * `Thread.setDefaultUncaughtExceptionHandler`：攔截到未捕捉例外時，先把完整堆疊（含執行緒
 * 名稱／時間戳／APP 版本／裝置型號）寫進 APP 外部檔案區 `crashlog/crash_yyyyMMdd_HHmmss.txt`
 * （[Context.getExternalFilesDir]，Scoped Storage 相容、不需額外權限），寫完【一定】交還給
 * 原本的預設 handler（[install] 呼叫當下先记住的 `previousHandler`），讓 APP 維持原本的正常
 * 閃退行為——鐵律：不吞掉例外、不試圖攔截讓 APP 假裝正常運作（那樣反而會讓 APP 卡在不可預期
 * 的壞狀態，比直接閃退更危險，也違反「不吞錯」的既有原則）。
 *
 * 下次啟動由 [LoginActivity]（APP 唯一入口，見 AndroidManifest LAUNCHER）呼叫
 * [notifyIfNewCrashLogExists]：比對上次已提示過的檔名（存 SharedPreferences），有新檔就跳
 * 一次 Toast，避免同一份記錄每次開 APP 都重複提示。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val CRASH_LOG_DIR_NAME = "crashlog"
    private const val PREFS_NAME = "crash_logger_prefs"
    private const val KEY_LAST_NOTIFIED_FILE_NAME = "last_notified_crash_file_name"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (e: Exception) {
                // 寫檔本身失敗也不能擋住後面把例外交還系統的動作，只記一筆 Logcat 供事後查
                Log.e(TAG, "寫入當機記錄失敗", e)
            }
            // 鐵律：不吞例外，寫完一律交還原本的預設 handler，維持正常閃退行為；
            // 極少數情況系統沒有預先設好任何 handler 才自己收尾結束進程。
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * v0.15.0：GL 渲染錯誤記錄（沒有真的當機，APP 仍存活）——檔名用 render_ 前綴跟 crash_ 區分，
     * [notifyIfNewCrashLogExists] 只認 crash_，不會把渲染記錄誤報成當機。寫檔失敗吞掉不影響直播。
     * 節流由呼叫端（[LiveActivity] 的 renderErrorCallback）負責。
     */
    fun writeRenderErrorLog(context: Context, thread: Thread, throwable: Throwable) {
        try {
            writeCrashLog(context, thread, throwable, filePrefix = "render_")
        } catch (e: Exception) {
            Log.e(TAG, "寫入渲染錯誤記錄失敗", e)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable, filePrefix: String = "crash_") {
        val dir = File(context.getExternalFilesDir(null), CRASH_LOG_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())
        val file = File(dir, "$filePrefix$fileTimestamp.txt")
        val stackTraceText = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()
        val header = "當機時間：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())}\n" +
            "執行緒：${thread.name}\n" +
            "APP 版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "裝置：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）\n\n"
        file.writeText(header + stackTraceText)
    }

    /** [LoginActivity] 每次啟動呼叫：有新的當機記錄（上次未提示過）就跳一次 Toast，不重複提示。 */
    fun notifyIfNewCrashLogExists(context: Context) {
        val dir = File(context.getExternalFilesDir(null), CRASH_LOG_DIR_NAME)
        // v0.15.0：只認 crash_ 前綴——render_ 是「沒當機」的渲染錯誤記錄，不該觸發當機提示
        val latestFile = dir.listFiles()?.filter { it.isFile && it.name.startsWith("crash_") }?.maxByOrNull { it.name } ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotifiedFileName = prefs.getString(KEY_LAST_NOTIFIED_FILE_NAME, null)
        if (lastNotifiedFileName == latestFile.name) return
        prefs.edit().putString(KEY_LAST_NOTIFIED_FILE_NAME, latestFile.name).apply()
        Toast.makeText(
            context,
            context.getString(R.string.crash_log_detected_toast_format, latestFile.name),
            Toast.LENGTH_LONG
        ).show()
    }
}
