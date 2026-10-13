package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

class ScreenshotFrameReceiptsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `another process instance can identify a saved image but not a reused URI`() {
        val directory = File(temporary.root, "receipts")
        val first = ScreenshotFrameReceipts(directory)
        val image = "Screenshot.png:1760000000:1286:2689:image/png"
        val uri = "content://media/external/images/media/321"
        first.publish(uri, image)
        assertTrue(ScreenshotFrameReceipts(directory).contains(uri, image))
        assertFalse(first.contains("content://media/external/images/media/322", image))
        assertFalse(first.contains(uri, "another image reused the URI"))
        first.publish(uri, "edited image")
        assertTrue(first.contains(uri, "edited image"))
        assertFalse(first.contains(uri, image))
    }

    @Test fun `unknown images create no cache and stale records expire`() {
        val directory = File(temporary.root, "receipts")
        val store = ScreenshotFrameReceipts(directory)
        assertFalse(store.contains("unknown", "image"))
        assertFalse(directory.exists())
        val image = "image"
        store.publish("known", image)
        directory.listFiles()!!.single { it.extension == "properties" }
            .setLastModified(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
        assertFalse(store.contains("known", image))
    }

    @Test fun `concurrent publication is atomic and the cache stays bounded`() {
        val directory = File(temporary.root, "receipts")
        val image = "image"
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 150).map { index ->
                pool.submit { ScreenshotFrameReceipts(directory).publish("content://image/$index", image) }
            }
            jobs.forEach { it.get() }
            assertEquals(128, directory.listFiles()!!.count { it.extension == "properties" })
            assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
            val reader = ScreenshotFrameReceipts(directory)
            assertEquals(128, (0 until 150).count { reader.contains("content://image/$it", image) })
        } finally {
            pool.shutdownNow()
        }
    }
}
