package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class PreparedWallpaperCacheTest {
    private class Worker : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.addLast(command) }
        fun finish() { work.removeFirst().run() }
    }
    private class Fixture {
        val worker = Worker()
        val disposed = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val cache = PreparedWallpaperCache<String>(worker, { disposed += it }, { failures += it })
        fun read() = cache.use { it }
    }

    @Test fun coldGestureNeverReadsWallpaperAndUsesOnlyFinishedPreparation() {
        val f = Fixture()
        var loads = 0
        assertNull(f.read())
        f.cache.request { loads++; "wallpaper" }
        repeat(10) { f.cache.request { error("duplicate load") }; assertNull(f.read()) }
        assertEquals(0, loads)
        assertEquals(1, f.worker.work.size)
        f.worker.finish()
        assertEquals("wallpaper", f.read())
        assertEquals(1, loads)
    }

    @Test fun unavailableWallpaperKeepsNativeFallbackUntilLifecycleRearmsPreparation() {
        val f = Fixture()
        f.cache.request { null }
        f.worker.finish()
        assertNull(f.read())
        f.cache.request { error("must not retry on every gesture") }
        assertTrue(f.worker.work.isEmpty())
        f.cache.retryMissing()
        f.cache.request { "after-unlock" }
        f.worker.finish()
        assertEquals("after-unlock", f.read())
    }

    @Test fun preparationExceptionCannotEscapeIntoGestureAndHasBoundedRetry() {
        val f = Fixture()
        f.cache.request { throw SecurityException("temporarily unavailable") }
        f.worker.finish()
        assertEquals(1, f.failures.size)
        assertNull(f.read())
        repeat(10) { f.cache.request { "too soon" } }
        assertTrue(f.worker.work.isEmpty())
        f.cache.retryMissing()
        f.cache.request { "recovered" }
        f.worker.finish()
        assertEquals("recovered", f.read())
    }

    @Test fun wallpaperChangeRejectsAndDisposesLateOldPixels() {
        val f = Fixture()
        f.cache.request { "old" }
        f.cache.invalidate()
        f.cache.request { "new" }
        f.worker.finish()
        assertNull(f.read())
        assertEquals(listOf("old"), f.disposed)
        f.worker.finish()
        assertEquals("new", f.read())
    }

    @Test fun rotationAndFreeformSizesReuseTheSameSourceWithoutAnotherLoad() {
        val f = Fixture()
        var loads = 0
        f.cache.request { loads++; "source" }
        f.worker.finish()
        for ((width, height) in listOf(1200 to 2608, 2608 to 1200, 800 to 600)) {
            assertEquals("source/${width}x$height", f.cache.use { "$it/${width}x$height" })
            f.cache.request { error("dimension changes must not reread wallpaper") }
        }
        assertEquals(1, loads)
        assertTrue(f.worker.work.isEmpty())
        assertTrue(f.disposed.isEmpty())
    }

    @Test fun wakingDuringPreparationDoesNotAbandonOrDuplicateTheLoad() {
        val f = Fixture()
        f.cache.request { "in-flight" }
        repeat(10) { f.cache.retryMissing(); f.cache.request { error("duplicate wake load") } }
        assertEquals(1, f.worker.work.size)
        f.worker.finish()
        assertEquals("in-flight", f.read())
    }

    @Test fun hotReloadRetirementDisposesLateCompletionAndCannotAllocateAgain() {
        val f = Fixture()
        f.cache.request { "old-generation" }
        f.cache.close()
        f.worker.finish()
        assertEquals(listOf("old-generation"), f.disposed)
        assertNull(f.read())
        f.cache.retryMissing()
        f.cache.invalidate()
        f.cache.request { error("retired cache") }
        assertTrue(f.worker.work.isEmpty())
    }

    @Test fun screenOnRetainsReadyWallpaperAndRenderingFailureDoesNotCorruptIt() {
        val f = Fixture()
        f.cache.request { "ready" }
        f.worker.finish()
        f.cache.retryMissing()
        f.cache.request { error("unnecessary reload") }
        assertTrue(f.worker.work.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            f.cache.use { throw IllegalStateException("surface unavailable") }
        }
        assertEquals("ready", f.read())
        f.cache.close()
        assertEquals(listOf("ready"), f.disposed)
    }
}
