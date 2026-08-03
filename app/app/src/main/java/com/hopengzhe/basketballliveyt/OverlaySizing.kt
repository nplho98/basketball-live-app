package com.hopengzhe.basketballliveyt

import kotlin.math.roundToInt

/** 不依賴 Android 的圖層尺寸換算，供直播畫面與單元測試共用。 */
object OverlaySizing {
    data class BaseResolution(val width: Int, val height: Int)
    data class BitmapSize(val width: Int, val height: Int)

    fun baseResolution(
        streamWidth: Int,
        streamHeight: Int,
        recordWidth: Int,
        recordHeight: Int
    ): BaseResolution = BaseResolution(
        width = maxOf(streamWidth, recordWidth).coerceAtLeast(1),
        height = maxOf(streamHeight, recordHeight).coerceAtLeast(1)
    )

    fun scalePercent(bitmapSize: Int, baseSize: Int): Float =
        bitmapSize * 100f / baseSize.coerceAtLeast(1)

    /**
     * 先重現舊版依直播尺寸取整的像素數，再按基準解析度放大，避免浮點取整讓既有版面位移一像素。
     */
    fun overlayBitmapSize(
        streamWidth: Int,
        streamHeight: Int,
        base: BaseResolution,
        widthRatio: Float,
        heightRatio: Float
    ): BitmapSize {
        val safeStreamWidth = streamWidth.coerceAtLeast(1)
        val safeStreamHeight = streamHeight.coerceAtLeast(1)
        val oldWidth = (safeStreamWidth * widthRatio).toInt().coerceAtLeast(1)
        val oldHeight = (safeStreamHeight * heightRatio).toInt().coerceAtLeast(1)
        return BitmapSize(
            width = (oldWidth * base.width.toFloat() / safeStreamWidth).roundToInt().coerceAtLeast(1),
            height = (oldHeight * base.height.toFloat() / safeStreamHeight).roundToInt().coerceAtLeast(1)
        )
    }
}
