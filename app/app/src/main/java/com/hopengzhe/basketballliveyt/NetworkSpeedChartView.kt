package com.hopengzhe.basketballliveyt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 一鍵測速用的簡易上傳速率曲線：測速期間（時長由 [reset] 傳入）即時畫出每個取樣點的 Mbps 折線，
 * 並疊一條 [THRESHOLD_MBPS]（7 Mbps）虛線標準參考線，方便跟現場實測曲線比對是否夠充裕。
 * 純自繪 Canvas，不引入圖表函式庫——需求只是讓 Boss 看到趨勢與是否有掉速，
 * 不是要精確座標軸的分析圖表。
 */
class NetworkSpeedChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 相對測速開始的經過毫秒 → 該取樣點的 Mbps
    private val samples = mutableListOf<Pair<Long, Double>>()
    private var totalDurationMs = 60_000L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA726")
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val thresholdLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA726")
        textSize = 24f
    }

    /** 開始新一輪測速前呼叫，清空舊曲線。 */
    fun reset(totalDurationMs: Long) {
        this.totalDurationMs = totalDurationMs
        samples.clear()
        invalidate()
    }

    /** 每完成一次區塊上傳就呼叫一次，即時把新的取樣點畫上曲線。 */
    fun addSample(elapsedMs: Long, mbps: Double) {
        samples.add(elapsedMs to mbps)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 7 Mbps 是「充裕」門檻參考線（見設定頁測速說明），一律畫出來方便跟實測曲線比對，
        // Y 軸最大值也一併把門檻線納入考量，避免曲線都在門檻以下時，門檻線被擠出畫面外。
        val maxMbps = (samples.maxOfOrNull { it.second } ?: 0.0)
            .coerceAtLeast(THRESHOLD_MBPS)
            .let { it * 1.1 }

        val thresholdY = height - (THRESHOLD_MBPS / maxMbps * height).toFloat()
        canvas.drawLine(0f, thresholdY, width.toFloat(), thresholdY, thresholdPaint)
        canvas.drawText("${THRESHOLD_MBPS.toInt()} Mbps", 8f, thresholdY - 6f, thresholdLabelPaint)

        if (samples.size < 2) return

        val path = Path()
        samples.forEachIndexed { index, (elapsedMs, mbps) ->
            val x = (elapsedMs.toFloat() / totalDurationMs) * width
            val y = height - (mbps / maxMbps * height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private companion object {
        const val THRESHOLD_MBPS = 7.0
    }
}
