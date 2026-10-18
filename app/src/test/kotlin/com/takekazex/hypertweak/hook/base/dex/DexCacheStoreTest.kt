package com.takekazex.hypertweak.hook.base.dex

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DexCacheStoreTest {
    private lateinit var cacheDir: File

    private object Feature : DexFeature {
        override val id = "test.feature"
        override val schema = 1
    }

    private object NewerFeature : DexFeature {
        override val id = "test.feature"
        override val schema = 2
    }

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("hypertweak-dex-cache").toFile()
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun apk(bytes: String = "apk"): File =
        File(cacheDir, "target-${System.nanoTime()}.apk").apply {
            writeText(bytes)
            setLastModified(1_700_000_000_000L)
        }

    @Test
    fun `round trips values for the same build identity`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("first" to "La;->a()V", "second" to ""))

        val loaded = DexCacheStore(Feature, cacheDir).read(apk)

        assertEquals(mapOf("first" to "La;->a()V", "second" to ""), loaded)
    }

    @Test
    fun `rejects an APK with a different size`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"))
        apk.appendText("changed")

        assertNull(DexCacheStore(Feature, cacheDir).read(apk))
    }

    @Test fun `rejects replaced bytes even when path size and mtime match`() {
        val apk = apk("old")
        val timestamp = apk.lastModified()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("target" to "old target"))
        apk.writeText("new")
        assertEquals(true, apk.setLastModified(timestamp))

        assertNull(DexCacheStore(Feature, cacheDir).read(apk))
    }

    @Test fun `atomic replacement leaves complete values and no temporary file`() {
        val apk = apk()
        val store = DexCacheStore(Feature, cacheDir)
        store.write(apk, mapOf("removed" to "old"))
        store.write(apk, mapOf("new" to "complete"))
        assertEquals(mapOf("new" to "complete"), store.read(apk))
        assertEquals(emptyList<String>(), cacheDir.listFiles()!!.filter { it.extension == "tmp" }.map { it.name })
    }

    @Test
    fun `rejects a different feature schema`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"))

        assertNull(DexCacheStore(NewerFeature, cacheDir).read(apk))
    }

    @Test
    fun `rejects a different install path with identical size and mtime`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"))
        val copy = File(apk.parentFile, "copy-${System.nanoTime()}.apk")
        apk.copyTo(copy)
        copy.setLastModified(apk.lastModified())

        assertNull(DexCacheStore(Feature, cacheDir).read(copy))
    }

    @Test
    fun `rejects a corrupt cache file`() {
        val apk = apk()
        val file = File(cacheDir, "hypertweak_dex_test.feature.properties")
        file.writeText("not a properties file\u0000")

        assertNull(DexCacheStore(Feature, cacheDir).read(apk))
    }

    @Test
    fun `read without a cache file returns null`() {
        assertNull(DexCacheStore(Feature, cacheDir).read(apk()))
    }
}
