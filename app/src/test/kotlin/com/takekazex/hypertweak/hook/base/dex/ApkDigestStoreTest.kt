package com.takekazex.hypertweak.hook.base.dex

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ApkDigestStoreTest {
    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("apk-digest").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun `a persisted digest is reused across store instances without reading the bytes`() = withDir { dir ->
        val apk = File(dir, "base.apk").apply { writeText("AAAA") }
        assumeNotNull(ApkFileStat.of(apk))
        val cache = File(dir, "cache/" + ApkDigestStore.FILE_NAME)
        val expected = ApkDigestStore.sha256(apk)
        assertArrayEquals(expected, ApkDigestStore(cache, "fp").digest(apk))
        assertTrue(cache.isFile)
        // Tamper with the stored digest: a second process must serve it, proving no re-read.
        val forged = "f".repeat(64)
        cache.writeText(cache.readText().replace(Regex("\\|[0-9a-f]{64}"), "|$forged"))
        assertEquals(forged, ApkDigestStore(cache, "fp").digest(apk).joinToString("") { "%02x".format(it) })
    }

    @Test fun `equal size and mtime replacement invalidates the persisted digest`() = withDir { dir ->
        val apk = File(dir, "base.apk").apply { writeText("AAAA") }
        assumeNotNull(ApkFileStat.of(apk))
        val cache = File(dir, ApkDigestStore.FILE_NAME)
        ApkDigestStore(cache, "fp").digest(apk)
        val modified = apk.lastModified()
        Thread.sleep(20)
        apk.writeText("BBBB"); check(apk.setLastModified(modified))
        assertArrayEquals(ApkDigestStore.sha256(apk), ApkDigestStore(cache, "fp").digest(apk))
    }

    @Test fun `a different build fingerprint never reuses a digest`() = withDir { dir ->
        val apk = File(dir, "base.apk").apply { writeText("AAAA") }
        assumeNotNull(ApkFileStat.of(apk))
        val cache = File(dir, ApkDigestStore.FILE_NAME)
        ApkDigestStore(cache, "old").digest(apk)
        cache.writeText(cache.readText().replace(Regex("\\|[0-9a-f]{64}"), "|" + "0".repeat(64)))
        assertArrayEquals(ApkDigestStore.sha256(apk), ApkDigestStore(cache, "new").digest(apk))
    }
}
