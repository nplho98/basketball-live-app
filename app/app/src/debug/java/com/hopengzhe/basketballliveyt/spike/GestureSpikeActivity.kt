package com.hopengzhe.basketballliveyt.spike

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.hopengzhe.basketballliveyt.databinding.ActivityGestureSpikeBinding
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.generic.GenericStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 獨立 debug 預覽；不連線、不錄影、不開啟麥克風。 */
class GestureSpikeActivity : AppCompatActivity(), ConnectChecker {
    private lateinit var binding: ActivityGestureSpikeBinding
    private lateinit var stream: GenericStream
    private lateinit var camera: Camera2Source
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var ready = false
    @Volatile private var mode = 0 // 0=左側 25%，1=右側 25%，2=全畫面（診斷用）
    private var lastDump = 0L // 僅由 worker 存取。
    private var surfaceReady = false
    private var listenerAttached = false
    private var recognizer: GestureRecognizer? = null // 僅由 worker 存取。
    private var lastAccepted = 0L // 相機 callback 執行緒專用。
    private var previousSample = 0L // 以下遲滯與計時狀態僅由 worker 存取。
    private var sampleGeneration = -1
    private var candidate = "無"
    private var repeats = 0
    private var confirmed = "無"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGestureSpikeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camera = Camera2Source(this)
        stream = GenericStream(this, this, camera, NoAudioSource())
        binding.previewFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRoi() }
        binding.switchSide.setOnClickListener {
            mode = (mode + 1) % 3
            generation.incrementAndGet()
            binding.confirmed.text = "無"
            binding.raw.text = "raw: 等待新區域取樣"
            updateRoi()
        }
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startPreview() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { startPreview() }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopPreview() }
        })
        worker.execute {
            try {
                recognizer = GestureRecognizer.createFromOptions(this,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.IMAGE).setNumHands(1).build())
                ready = true
                runOnUiThread { if (!isDestroyed) binding.raw.text = "raw: 等待影格" }
            } catch (e: Exception) { reportError("模型初始化失敗", e) }
        }
        if (!hasCameraPermission()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 71)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            if (hasCameraPermission()) startPreview()
            else binding.raw.text = "未取得相機權限；請在應用程式設定允許後重開"
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        startPreview()
        handler.post(temperatureTick)
    }

    private fun startPreview() {
        if (!active || !surfaceReady || binding.previewSurface.width == 0 || !hasCameraPermission() || stream.isOnPreview) return
        try {
            check(stream.prepareVideo(1280, 720, 2_300_000, 30, 2, 0)) { "prepareVideo 回傳 false" }
            // 沿用 Phase0：先 init source，再掛 ImageReader，避免寬高為 0。
            camera = Camera2Source(this)
            camera.setCameraCallback(object : CameraCallbacks {
                override fun onCameraChanged(facing: CameraHelper.Facing) = Unit
                override fun onCameraOpened() = Unit
                override fun onCameraDisconnected() = Unit
                override fun onCameraError(error: String) { reportError("相機錯誤", IllegalStateException(error)) }
            })
            stream.changeVideoSource(camera)
            camera.addImageListener(ImageFormat.YUV_420_888, 2, true, object : Camera2ApiManager.ImageCallback {
                override fun onImageAvailable(image: Image) { sample(image) }
            })
            listenerAttached = true
            stream.startPreview(binding.previewSurface)
        } catch (e: Exception) { stopPreview(); reportError("預覽啟動失敗", e) }
    }

    private fun stopPreview() {
        generation.incrementAndGet()
        if (stream.isOnPreview) stream.stopPreview()
        // 先停相機再移除 listener；反過來會觸發 RootEncoder 關閉後重開相機。
        if (listenerAttached) { camera.removeImageListener(); listenerAttached = false }
    }

    private fun sample(image: Image) {
        val now = SystemClock.elapsedRealtime()
        if (!active || !ready || now - lastAccepted < 334 || !busy.compareAndSet(false, true)) return
        lastAccepted = now
        val token = generation.get()
        // autoClose=true：callback 返回後 Image 已失效，不能交給背景執行緒。
        val bitmap = try { cropYuv(image, mode) } catch (e: Exception) {
            busy.set(false); reportError("影格轉換失敗", e); return
        }
        try {
            worker.execute {
                try {
                    if (!active || token != generation.get()) return@execute
                    if (sampleGeneration != token) {
                        sampleGeneration = token; candidate = "無"; repeats = 0; confirmed = "無"; previousSample = 0
                    }
                    if (now - lastDump >= 1000) { lastDump = now; dumpInput(bitmap) }
                    val input = BitmapImageBuilder(bitmap).build()
                    val started = SystemClock.elapsedRealtimeNanos()
                    val result = try { recognizer!!.recognize(input) } finally { input.close() }
                    val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    val top = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
                    val name = top?.categoryName() ?: "None"
                    val value = when (name) { "Pointing_Up" -> "比 1"; "Victory" -> "比 2"; else -> "無" }
                    if (value == candidate) repeats++ else { candidate = value; repeats = 1 }
                    if (repeats >= 3) confirmed = candidate
                    val fps = if (previousSample == 0L) 0.0 else 1000.0 / (now - previousSample)
                    previousSample = now
                    val label = confirmed
                    val raw = String.format(Locale.TAIWAN, "raw: %s %.2f｜連續 %d/3", name, top?.score() ?: 0f, repeats.coerceAtMost(3))
                    runOnUiThread {
                        if (active && token == generation.get()) {
                            binding.confirmed.text = label
                            binding.raw.text = raw
                            binding.timing.text = String.format(Locale.TAIWAN, "推論 %.1f ms｜取樣 %.2f fps", ms, fps)
                        }
                    }
                } catch (e: Exception) {
                    candidate = "無"; repeats = 0; confirmed = "無"
                    reportError("辨識失敗", e)
                } finally { bitmap.recycle(); busy.set(false) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) { bitmap.recycle(); busy.set(false) }
    }

    /** 只轉換原始 1280×720 畫面的邊緣 25%；尊重每個 YUV plane 的兩種 stride。 */
    private fun cropYuv(image: Image, mode: Int): Bitmap {
        val crop = image.cropRect
        val width = if (mode == 2) crop.width() else crop.width() / 4
        val height = crop.height()
        val left = crop.left + if (mode == 1) crop.width() - width else 0
        val planes = image.planes
        val buffers = planes.map { it.buffer.duplicate() }
        fun read(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            val b = buffers[plane]
            return b.get(b.position() + y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = left + x
            val sy = crop.top + y
            val yy = (read(0, sx, sy) - 16).coerceAtLeast(0)
            val u = read(1, sx / 2, sy / 2) - 128
            val v = read(2, sx / 2, sy / 2) - 128
            val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun updateRoi() {
        val w = if (mode == 2) binding.previewFrame.width else binding.previewFrame.width / 4
        binding.roi.layoutParams = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT,
            if (mode == 1) Gravity.RIGHT else Gravity.LEFT)
        binding.switchSide.text = when (mode) {
            0 -> "目前左側 25%｜點我切右側"
            1 -> "目前右側 25%｜點我切全畫面"
            else -> "目前全畫面｜點我切左側"
        }
    }

    /** 把真正餵給 MediaPipe 的那張圖存檔，用 adb pull 回來目視確認裁切與色彩是否正常。 */
    private fun dumpInput(bitmap: Bitmap) {
        try {
            java.io.File(getExternalFilesDir(null), "gesture_input.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
        } catch (_: Exception) { /* 診斷用，失敗不影響量測 */ }
    }

    private val temperatureTick = object : Runnable {
        override fun run() {
            if (!active) return
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            binding.temperature.text = if (tenths == Int.MIN_VALUE) "電池：無讀值｜機身：無公開讀值"
                else String.format(Locale.TAIWAN, "電池 %.1f °C｜機身：無公開讀值", tenths / 10.0)
            handler.postDelayed(this, 5_000)
        }
    }

    private fun reportError(message: String, e: Exception) {
        android.util.Log.e("GestureSpike", message, e)
        runOnUiThread { if (!isDestroyed) { binding.confirmed.text = "無"; binding.raw.text = "$message：${e.message}" } }
    }

    override fun onPause() {
        active = false
        handler.removeCallbacks(temperatureTick)
        stopPreview()
        binding.confirmed.text = "無"
        super.onPause()
    }

    override fun onDestroy() {
        ready = false
        stream.release()
        // close 與 recognize 使用同一執行緒，避免釋放正在使用的 native 資源。
        worker.execute { recognizer?.close(); recognizer = null }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onConnectionStarted(url: String) = Unit
    override fun onConnectionSuccess() = Unit
    override fun onConnectionFailed(reason: String) = Unit
    override fun onNewBitrate(bitrate: Long) = Unit
    override fun onDisconnect() = Unit
    override fun onAuthError() = Unit
    override fun onAuthSuccess() = Unit
}
