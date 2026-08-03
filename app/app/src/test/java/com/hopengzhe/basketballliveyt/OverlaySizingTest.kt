package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlaySizingTest {

    @Test
    fun `720p 直播搭配 1080p 錄影維持原本比例但提高來源解析度`() {
        val base = OverlaySizing.baseResolution(1280, 720, 1920, 1080)
        val bitmap = OverlaySizing.overlayBitmapSize(1280, 720, base, 0.46f, 0.125f)

        assertEquals(1920, base.width)
        assertEquals(1080, base.height)
        assertEquals(882, bitmap.width)
        assertEquals(135, bitmap.height)
        assertEquals(588 * 100f / 1280, OverlaySizing.scalePercent(bitmap.width, base.width), 0.001f)
        assertEquals(90 * 100f / 720, OverlaySizing.scalePercent(bitmap.height, base.height), 0.001f)
    }

    @Test
    fun `1080p 直播搭配 1080p 錄影維持相同比例`() {
        val base = OverlaySizing.baseResolution(1920, 1080, 1920, 1080)
        val bitmap = OverlaySizing.overlayBitmapSize(1920, 1080, base, 0.46f, 0.125f)

        assertEquals(1920, base.width)
        assertEquals(1080, base.height)
        assertEquals(883, bitmap.width)
        assertEquals(135, bitmap.height)
        assertEquals(883 * 100f / 1920, OverlaySizing.scalePercent(bitmap.width, base.width), 0.001f)
        assertEquals(135 * 100f / 1080, OverlaySizing.scalePercent(bitmap.height, base.height), 0.001f)
    }
}
