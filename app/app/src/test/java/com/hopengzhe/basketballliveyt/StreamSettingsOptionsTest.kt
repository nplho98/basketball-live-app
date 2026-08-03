package com.hopengzhe.basketballliveyt

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSettingsOptionsTest {

    @Test
    fun `default and low latency bitrates are both current minimum`() {
        assertEquals("3300 Kbps", StreamPrefs.LOW_LATENCY_BITRATE)
        assertEquals("3300 Kbps", StreamPrefs.DEFAULT_BITRATE)
    }

    @Test
    fun `removed and invalid resolution values fall back to default`() {
        assertEquals(StreamPrefs.DEFAULT_RESOLUTION, StreamPrefs.coerceResolution("854x480（480p）"))
        assertEquals(StreamPrefs.DEFAULT_RESOLUTION, StreamPrefs.coerceResolution("不支援的解析度"))
        assertEquals(StreamPrefs.DEFAULT_RESOLUTION, StreamPrefs.coerceResolution(null))
    }

    @Test
    fun `removed and invalid fps values fall back to default`() {
        assertEquals(StreamPrefs.DEFAULT_FPS, StreamPrefs.coerceFps("60 fps"))
        assertEquals(StreamPrefs.DEFAULT_FPS, StreamPrefs.coerceFps("120 fps"))
        assertEquals(StreamPrefs.DEFAULT_FPS, StreamPrefs.coerceFps(null))
    }

    @Test
    fun `removed empty and invalid bitrate values fall back to default`() {
        assertEquals(StreamPrefs.DEFAULT_BITRATE, StreamPrefs.coerceBitrate("1400 Kbps"))
        assertEquals(StreamPrefs.DEFAULT_BITRATE, StreamPrefs.coerceBitrate("2300 Kbps"))
        assertEquals(StreamPrefs.DEFAULT_BITRATE, StreamPrefs.coerceBitrate(""))
        assertEquals(StreamPrefs.DEFAULT_BITRATE, StreamPrefs.coerceBitrate("不支援的碼率"))
        assertEquals(StreamPrefs.DEFAULT_BITRATE, StreamPrefs.coerceBitrate(null))
    }

    @Test
    fun `valid saved stream values remain unchanged`() {
        assertEquals("1920x1080（1080p）", StreamPrefs.coerceResolution("1920x1080（1080p）"))
        assertEquals("1280x720（720p）", StreamPrefs.coerceResolution("1280x720（720p）"))
        assertEquals("24 fps", StreamPrefs.coerceFps("24 fps"))
        assertEquals("30 fps", StreamPrefs.coerceFps("30 fps"))
        assertEquals("3300 Kbps", StreamPrefs.coerceBitrate("3300 Kbps"))
        assertEquals("4000 Kbps", StreamPrefs.coerceBitrate("4000 Kbps"))
    }

}
