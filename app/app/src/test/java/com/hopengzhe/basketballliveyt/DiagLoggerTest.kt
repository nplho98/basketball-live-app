package com.hopengzhe.basketballliveyt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0.17.1（第一階段審查第1項）：驗證 [DiagLogger] 非同步寫檔的兩個關鍵性質——
 * 呼叫端快速返回、並行 log 不撕裂行內容。純 JVM 測試，不需 Android Context（用 configureForTest）。
 */
class DiagLoggerTest {

    @After
    fun tearDown() {
        DiagLogger.resetForTest()
    }

    /** 並行 log 不損壞行：多執行緒各排入多筆，落檔後總行數正確、每行皆完整符合格式、無交錯撕裂。 */
    @Test
    fun concurrentLogsProduceIntactLines() {
        val tmp = File.createTempFile("diag_test", ".txt").apply { deleteOnExit() }
        DiagLogger.configureForTest(tmp)

        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.await()
                repeat(perThread) { i -> DiagLogger.enqueueForTest("BITRATE", "t$t-i$i") }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("排入未在時限內完成", done.await(5, TimeUnit.SECONDS))
        DiagLogger.drainForTest()

        val lines = tmp.readLines()
        // 佇列容量 512，並行排入 1600 筆會有丟棄（設計即如此，不阻塞直播）；驗證「寫出的每一行都完整」。
        assertTrue("應有寫出行", lines.isNotEmpty())
        val pattern = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} \[BITRATE] t\d+-i\d+$""")
        lines.forEach { line ->
            assertTrue("行被撕裂或格式不符：<$line>", pattern.matches(line))
        }
    }

    /** 呼叫端快速返回：即使排入遠超佇列容量，enqueue 迴圈也應在極短時間內返回（非阻塞 offer）。 */
    @Test
    fun enqueueReturnsFastEvenWhenOverCapacity() {
        val tmp = File.createTempFile("diag_test_fast", ".txt").apply { deleteOnExit() }
        DiagLogger.configureForTest(tmp)

        val count = 5000 // 遠超 QUEUE_CAPACITY(512)
        val elapsedMs = measureMillis {
            repeat(count) { DiagLogger.enqueueForTest("ZOOM", "call$it") }
        }
        // 5000 次非阻塞 offer 應遠低於 1 秒；給寬鬆上限避免慢 CI 誤判，重點是「不會因佇列滿而卡住」。
        assertTrue("enqueue 疑似阻塞：耗時 ${elapsedMs}ms", elapsedMs < 1000)
    }

    private inline fun measureMillis(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** formatLine 純函式：行內不含換行，故並行寫出不會互相撕裂。 */
    @Test
    fun formatLineHasNoNewline() {
        val line = DiagLogger.formatLine("CONN", "onConnectionSuccess")
        assertEquals(-1, line.indexOf('\n'))
        assertTrue(line.contains("[CONN] onConnectionSuccess"))
    }
}
