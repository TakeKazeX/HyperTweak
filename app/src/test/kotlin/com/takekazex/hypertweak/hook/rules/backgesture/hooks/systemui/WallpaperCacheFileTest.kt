package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class WallpaperCacheFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun path() = File(temporary.root, "private-wallpaper/source.htwp")

    @Test fun replacementRuntimeReadsSavedPixelsOnlyForTheCurrentWallpaper() {
        val file = path()
        val pixels = "prepared-png".toByteArray()
        WallpaperCacheFile(file).write(41, pixels)
        val replacement = WallpaperCacheFile(file)
        assertArrayEquals(pixels, replacement.read(41))
        assertNull(replacement.read(42))
        assertNull(replacement.read(0))
    }

    @Test fun wallpaperSwitchAtomicallyReplacesTheOneCachedRecord() {
        val file = path()
        WallpaperCacheFile(file).write(41, byteArrayOf(1, 2, 3))
        WallpaperCacheFile(file).write(42, byteArrayOf(4, 5))
        assertNull(WallpaperCacheFile(file).read(41))
        assertArrayEquals(byteArrayOf(4, 5), WallpaperCacheFile(file).read(42))
        assertEquals(listOf("source.htwp"), requireNotNull(file.parentFile).list()!!.toList())
    }

    @Test fun corruptPixelsAndTruncatedRecordsAreMisses() {
        val file = path()
        val disk = WallpaperCacheFile(file)
        disk.write(41, byteArrayOf(1, 2, 3))
        RandomAccessFile(file, "rw").use { it.seek(it.length() - 1); it.writeByte(99) }
        assertNull(disk.read(41))
        disk.write(41, byteArrayOf(1, 2, 3))
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
        assertNull(disk.read(41))
    }

    @Test fun incompatibleVersionsAndOversizedRecordsCannotBeDecoded() {
        val file = path()
        val disk = WallpaperCacheFile(file)
        disk.write(41, byteArrayOf(1))
        RandomAccessFile(file, "rw").use { it.seek(4); it.writeInt(999) }
        assertNull(disk.read(41))
        RandomAccessFile(file, "rw").use { it.setLength(WallpaperCacheFile.MAX_BYTES + 25L) }
        assertNull(disk.read(41))
    }

    @Test fun invalidWritesPreserveThePreviousCompleteCache() {
        val file = path()
        val disk = WallpaperCacheFile(file)
        disk.write(41, byteArrayOf(1, 2))
        assertThrows(IOException::class.java) { disk.write(42, byteArrayOf()) }
        assertThrows(IOException::class.java) { disk.write(0, byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(1, 2), disk.read(41))
    }

    @Test fun missingOrClearedCacheIsAnOrdinaryMiss() {
        val file = path()
        val disk = WallpaperCacheFile(file)
        assertNull(disk.read(41))
        disk.write(41, byteArrayOf(1))
        assertTrue(file.delete())
        assertNull(disk.read(41))
    }
}
