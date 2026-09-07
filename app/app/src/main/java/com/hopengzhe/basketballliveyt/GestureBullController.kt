package com.hopengzhe.basketballliveyt

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** 正式直播畫面的手勢控制器；只負責辨識，不包含 UI 或診斷影像存檔。 */
class GestureBullController(
    private val context: Context,
    private val useRightSide: Boolean,
    private val onGesture: (visible: Boolean) -> Unit
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var recognizer: GestureRecognizer? = null
    @Volatile private var active = false
    private var lastAccepted = 0L
    private var candidate: Boolean? = null
    private var repeats = 0
    private var confirmed: Boolean? = null

    fun start() {
        if (active) return
        active = true
        worker.execute {
            try {
                recognizer = GestureRecognizer.createFromOptions(
                    context,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(1)
                        .build()
                )
            } catch (e: Exception) {
                Log.e(TAG, "手勢模型初始化失敗", e)
            }
        }
    }

    fun onImage(image: Image) {
        val now = SystemClock.elapsedRealtime()
        if (!active || now - lastAccepted < SAMPLE_INTERVAL_MS || !busy.compareAndSet(false, true)) return
        lastAccepted = now
        val bitmap = try {
            cropYuv(image)
        } catch (e: Exception) {
            busy.set(false)
            Log.e(TAG, "手勢影格轉換失敗", e)
            return
        }
        try {
            worker.execute {
                try {
                    val currentRecognizer = recognizer ?: return@execute
                    val input = BitmapImageBuilder(bitmap).build()
                    val result = try { currentRecognizer.recognize(input) } finally { input.close() }
                    val name = result.gestures().firstOrNull()?.maxByOrNull { it.score() }?.categoryName()
                    val visible = when (name) {
                        "Pointing_Up" -> true
                        "Victory" -> false
                        else -> null
                    } ?: return@execute
                    if (visible == candidate) repeats++ else {
                        candidate = visible
                        repeats = 1
                    }
                    if (repeats >= CONFIRM_REPEATS && confirmed != visible) {
                        confirmed = visible
                        onGesture(visible)
                    }
                } catch (e: Exception) {
                    candidate = null
                    repeats = 0
                    Log.e(TAG, "手勢辨識失敗", e)
                } finally {
                    bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            bitmap.recycle()
            busy.set(false)
        }
    }

    fun release() {
        if (!active) return
        active = false
        worker.execute {
            recognizer?.close()
            recognizer = null
        }
        worker.shutdown()
    }

    /** 只取畫面左／右側 25%；依照每個 YUV plane 的 rowStride、pixelStride 與 cropRect 讀取。 */
    private fun cropYuv(image: Image): Bitmap {
        val crop = image.cropRect
        val width = crop.width() / 4
        val height = crop.height()
        val left = crop.left + if (useRightSide) crop.width() - width else 0
        val planes = image.planes
        val buffers = planes.map { it.buffer.duplicate() }
        fun read(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            val buffer = buffers[plane]
            return buffer.get(buffer.position() + y * p.rowStride + x * p.pixelStride).toInt() and 255
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

    private companion object {
        const val TAG = "GestureBull"
        const val SAMPLE_INTERVAL_MS = 334L
        const val CONFIRM_REPEATS = 3
    }
}
