package com.hopengzhe.basketballliveyt.spike

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hopengzhe.basketballliveyt.databinding.ActivityPhase0SpikeBinding
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.BitmapSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 spike：驗證 RootEncoder 2.6.1 StreamBase 新管線（GenericStream）能否支撐
 * 「休息畫面真正釋放相機降溫」——Camera2Source ↔ BitmapSource 熱切換、RTMP session 不重建、
 * 音訊來源全程不動（Boss 拍板休息保留現場聲音）、GL 濾鏡掛載、同步錄影、zoom/曝光/AF 公開 API。
 *
 * 只存在於 debug build（src/debug/），正式 UI 無入口，adb 啟動：
 *   adb shell am start -n com.hopengzhe.basketballliveyt/.spike.Phase0SpikeActivity
 *
 * 驗證項目對照 .ai-sync 信箱 20260719_132000_codex_break_camera_review.md 的止損清單。
 * log 寫入 getExternalFilesDir()/spike/，adb pull 取回附在信箱回報。
 *
 * 恢復序列設計：單一 video source 的管線沒辦法「BitmapSource 續播同時開相機暖機」，
 * 改用 GL 全幅覆蓋濾鏡達成同等效果——切回相機前先蓋上休息圖濾鏡，等第一張有效影格
 * （公開 API addImageListener）＋重套 zoom/曝光後才移除，觀眾全程看不到暖機畫面。
 * 逾時 1.5 秒重開相機一次，再失敗退回 BitmapSource（照 Codex 規則）。
 */
class Phase0SpikeActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityPhase0SpikeBinding
    private lateinit var stream: GenericStream
    private lateinit var camSource: Camera2Source

    private var inBreak = false
    private var recording = false
    private var filterOn = false
    private var afLocked = false
    private var aeLocked = false

    // 休息前的相機狀態快照，恢復時重套
    private var savedZoom = 1f
    private var savedExposure = 0

    private var firstFrameGate: CompletableDeferred<Unit>? = null

    private lateinit var logFile: File
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.TAIWAN)

    private val prefs by lazy { getSharedPreferences("spike_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val TAG = "Phase0Spike"
        private const val GAME_BITRATE = 2_300_000
        private const val BREAK_BITRATE = 500_000
        private const val RESUME_TIMEOUT_MS = 1_500L
        private const val CYCLE_COUNT = 20
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhase0SpikeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        logFile = File(File(getExternalFilesDir(null), "spike").apply { mkdirs() },
            "spike_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())}.txt")

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)

        camSource = Camera2Source(this)
        camSource.setCameraCallback(cameraCallbacks)
        stream = GenericStream(this, this, camSource, MicrophoneSource())

        binding.rtmpUrlInput.setText(prefs.getString("rtmp_url", ""))

        binding.btnPreview.setOnClickListener {
            if (!stream.isOnPreview) {
                if (prepareIfNeeded()) {
                    stream.startPreview(binding.previewSurface)
                    binding.btnPreview.text = "停止預覽"
                    log("預覽開始")
                }
            } else {
                stream.stopPreview()
                binding.btnPreview.text = "開始預覽"
                log("預覽停止")
            }
        }

        binding.btnStream.setOnClickListener {
            if (!stream.isStreaming) {
                val url = binding.rtmpUrlInput.text.toString().trim()
                if (url.isEmpty()) { log("RTMP 網址空白，不開播"); return@setOnClickListener }
                prefs.edit().putString("rtmp_url", url).apply()
                if (prepareIfNeeded()) {
                    stream.startStream(url)
                    binding.btnStream.text = "收播"
                    log("startStream 已送出")
                }
            } else {
                stream.stopStream()
                binding.btnStream.text = "開始直播"
                log("stopStream（收播）完成，isStreaming=${stream.isStreaming}")
            }
        }

        binding.btnBreak.setOnClickListener {
            if (!inBreak) enterBreak() else lifecycleScope.launch { resumeFromBreak() }
        }

        binding.btnCycleTest.setOnClickListener { runCycleTest() }

        binding.btnRecord.setOnClickListener {
            if (!recording) {
                val path = File(File(getExternalFilesDir(null), "spike"),
                    "rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())}.mp4").absolutePath
                stream.startRecord(path) { status: RecordController.Status ->
                    log("錄影狀態：$status")
                }
                recording = true
                binding.btnRecord.text = "停止錄影"
                log("錄影開始：$path")
            } else {
                stream.stopRecord()
                recording = false
                binding.btnRecord.text = "開始錄影"
                log("錄影停止")
            }
        }

        binding.btnFilter.setOnClickListener {
            if (!filterOn) {
                val f = TextObjectFilterRender()
                stream.getGlInterface().addFilter(f)
                f.setText("SPIKE 主隊 34 : 28 客隊", 22f, Color.YELLOW)
                filterOn = true
                binding.btnFilter.text = "移除濾鏡"
                log("計分濾鏡已掛（TextObjectFilterRender）")
            } else {
                stream.getGlInterface().clearFilters()
                filterOn = false
                binding.btnFilter.text = "掛計分濾鏡"
                log("濾鏡已清除")
            }
        }

        binding.btnZoomIn.setOnClickListener { nudgeZoom(+0.5f) }
        binding.btnZoomOut.setOnClickListener { nudgeZoom(-0.5f) }
        binding.btnExpUp.setOnClickListener { nudgeExposure(+1) }
        binding.btnExpDown.setOnClickListener { nudgeExposure(-1) }

        binding.btnAfLock.setOnClickListener {
            afLocked = if (!afLocked) camSource.disableAutoFocus()
            else { camSource.enableAutoFocus(); false }
            binding.btnAfLock.text = if (afLocked) "AF解鎖" else "AF鎖定"
            log("AF ${if (afLocked) "鎖定（disableAutoFocus）" else "回自動（enableAutoFocus）"}，isAutoFocusEnabled=${camSource.isAutoFocusEnabled()}")
        }

        binding.btnAeLock.setOnClickListener {
            if (!aeLocked) { camSource.disableAutoExposure(); aeLocked = true }
            else { camSource.enableAutoExposure(); aeLocked = false }
            binding.btnAeLock.text = if (aeLocked) "AE解鎖" else "AE鎖定"
            log("AE ${if (aeLocked) "鎖定（disableAutoExposure）" else "回自動（enableAutoExposure）"}，isAutoExposureEnabled=${camSource.isAutoExposureEnabled()}")
        }

        binding.previewSurface.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN && !inBreak) {
                val ok = camSource.tapToFocus(v, event)
                log("tapToFocus(${event.x.toInt()},${event.y.toInt()}) → $ok")
            }
            true
        }

        log("spike 啟動，log 檔：${logFile.absolutePath}")
    }

    private fun prepareIfNeeded(): Boolean {
        if (stream.isStreaming || stream.isOnPreview) return true
        val v = stream.prepareVideo(1280, 720, GAME_BITRATE, 30, 2, 0)
        val a = stream.prepareAudio(44100, true, 128_000)
        log("prepareVideo=$v prepareAudio=$a（720p/30fps/${GAME_BITRATE / 1000}Kbps）")
        return v && a
    }

    // ---------- 休息／恢復 ----------

    private fun enterBreak() {
        savedZoom = camSource.getZoom()
        savedExposure = camSource.getExposure()
        val t0 = System.currentTimeMillis()
        stream.changeVideoSource(BitmapSource(buildBreakBitmap()))
        stream.setVideoBitrateOnFly(BREAK_BITRATE)
        inBreak = true
        binding.btnBreak.text = "恢復直播"
        log("進休息：changeVideoSource(BitmapSource) 耗時 ${System.currentTimeMillis() - t0}ms，" +
            "碼率降 ${BREAK_BITRATE / 1000}Kbps，快照 zoom=$savedZoom exposure=$savedExposure，" +
            "音訊來源未動（isStreaming=${stream.isStreaming}）")
    }

    /** 恢復序列：蓋濾鏡 → 切回相機 → 等第一張有效影格＋重套設定 → 移除濾鏡。回傳是否成功。 */
    private suspend fun resumeFromBreak(): Boolean {
        val cover = ImageObjectFilterRender().also {
            stream.getGlInterface().addFilter(it)
            it.setImage(buildBreakBitmap())
            it.setScale(100f, 100f)
            it.setPosition(0f, 0f)
        }
        var ok = false
        repeat(2) { attempt ->
            if (ok) return@repeat
            val t0 = System.currentTimeMillis()
            val gate = CompletableDeferred<Unit>()
            firstFrameGate = gate
            camSource = Camera2Source(this).also { it.setCameraCallback(cameraCallbacks) }
            stream.changeVideoSource(camSource)
            // addImageListener 兩個地雷（實機踩過）：①前兩參數是 (format, maxImages) 不是寬高，
            // 傳 320 會被當 ImageFormat 丟例外；②要在 changeVideoSource 之後掛——之前 source
            // 尚未 init，寬高為 0，ImageReader 直接拒建
            camSource.addImageListener(ImageFormat.YUV_420_888, 2, true, object : Camera2ApiManager.ImageCallback {
                override fun onImageAvailable(image: Image) {
                    firstFrameGate?.complete(Unit)
                }
            })
            val got = withTimeoutOrNull(RESUME_TIMEOUT_MS) { gate.await() } != null
            camSource.removeImageListener()
            if (got) {
                if (savedZoom > 0f) camSource.setZoom(savedZoom)
                camSource.setExposure(savedExposure)
                if (afLocked) camSource.disableAutoFocus()
                if (aeLocked) camSource.disableAutoExposure()
                // AE 收斂拿不到 CaptureResult（無反射前提），固定緩衝近似，見信箱 20260719_132900
                delay(300)
                ok = true
                log("恢復成功（第 ${attempt + 1} 次嘗試）：首幀+重套 耗時 ${System.currentTimeMillis() - t0}ms，" +
                    "zoom=${camSource.getZoom()} exposure=${camSource.getExposure()}")
            } else {
                log("恢復第 ${attempt + 1} 次逾時（${RESUME_TIMEOUT_MS}ms 無有效影格）")
            }
        }
        if (ok) {
            stream.setVideoBitrateOnFly(GAME_BITRATE)
            stream.requestKeyframe()
            inBreak = false
            binding.btnBreak.text = "進休息"
        } else {
            stream.changeVideoSource(BitmapSource(buildBreakBitmap()))
            log("兩次恢復皆失敗，退回 BitmapSource 維持休息畫面（可再按恢復或收播）")
        }
        stream.getGlInterface().removeFilter(cover)
        firstFrameGate = null
        return ok
    }

    /** 必過項目3：連續切換 20 次不得失敗。 */
    private fun runCycleTest() {
        binding.btnCycleTest.isEnabled = false
        lifecycleScope.launch {
            var pass = 0
            log("=== 連續切換測試開始（$CYCLE_COUNT 次）===")
            for (i in 1..CYCLE_COUNT) {
                if (!inBreak) enterBreak()
                delay(2_000)
                if (resumeFromBreak()) pass++ else log("!!! 第 $i 輪恢復失敗")
                delay(1_000)
                status("連切測試 $i/$CYCLE_COUNT，成功 $pass")
            }
            log("=== 連續切換測試結束：$pass/$CYCLE_COUNT 成功，isStreaming=${stream.isStreaming} ===")
            binding.btnCycleTest.isEnabled = true
        }
    }

    private fun buildBreakBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(10, 22, 48))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 96f; textAlign = Paint.Align.CENTER }
        c.drawText("中場休息", 640f, 300f, p)
        p.textSize = 48f; p.color = Color.YELLOW
        c.drawText("主隊 34 : 28 客隊", 640f, 420f, p)
        p.textSize = 32f; p.color = Color.LTGRAY
        c.drawText(timeFmt.format(Date()), 640f, 500f, p)
        return bmp
    }

    private fun nudgeZoom(delta: Float) {
        if (inBreak) return
        val range = camSource.getZoomRange()
        val z = (camSource.getZoom() + delta).coerceIn(range.lower, range.upper)
        camSource.setZoom(z)
        status("zoom=$z（範圍 ${range.lower}~${range.upper}）")
    }

    private fun nudgeExposure(delta: Int) {
        if (inBreak) return
        camSource.setExposure(camSource.getExposure() + delta)
        status("exposure=${camSource.getExposure()}")
    }

    // ---------- callbacks ----------

    private val cameraCallbacks = object : CameraCallbacks {
        override fun onCameraChanged(facing: CameraHelper.Facing) { log("相機 onCameraChanged：$facing") }
        override fun onCameraOpened() { log("相機 onCameraOpened（相機重新取得）") }
        override fun onCameraDisconnected() { log("相機 onCameraDisconnected（相機已釋放）") }
        override fun onCameraError(error: String) { log("相機 onCameraError：$error") }
    }

    override fun onConnectionStarted(url: String) { log("RTMP 連線開始") }
    override fun onConnectionSuccess() { log("RTMP 連線成功") }
    override fun onConnectionFailed(reason: String) { log("!!! RTMP 連線失敗：$reason") }
    override fun onNewBitrate(bitrate: Long) { status("上傳 ${bitrate / 1000}Kbps${if (inBreak) "（休息中）" else ""}") }
    override fun onDisconnect() { log("RTMP 已斷線") }
    override fun onAuthError() { log("!!! RTMP 認證失敗") }
    override fun onAuthSuccess() { log("RTMP 認證成功") }

    // ---------- log ----------

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        Log.i(TAG, msg)
        logFile.appendText(line + "\n")
        runOnUiThread {
            logLines.addLast(line)
            while (logLines.size > 30) logLines.removeFirst()
            binding.logText.text = logLines.joinToString("\n")
        }
    }

    private fun status(msg: String) { runOnUiThread { binding.statusText.text = msg } }

    override fun onDestroy() {
        super.onDestroy()
        if (stream.isRecording) stream.stopRecord()
        if (stream.isStreaming) stream.stopStream()
        if (stream.isOnPreview) stream.stopPreview()
        stream.release()
    }
}
