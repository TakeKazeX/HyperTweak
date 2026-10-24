package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.hook.base.DexKitCacheIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        File(cacheDir, "target-" + System.nanoTime() + ".apk").apply {
            writeText(bytes)
            setLastModified(1_700_000_000_000L)
        }

    private fun identityOf(apk: File): DexKitCacheIdentity =
        DexKitCacheIdentity.withFullDigest(requireNotNull(DexKitCacheIdentity.capture(apk)))

    @Test
    fun `round trips values for the same build identity`() {
        val apk = apk()
        val identity = identityOf(apk)
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("first" to "La;->a()V", "second" to ""), identity)

        val loaded = DexCacheStore(Feature, cacheDir).read(apk, identity)

        assertEquals(mapOf("first" to "La;->a()V", "second" to ""), loaded)
    }

    @Test
    fun `rejects an APK with a different size`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"), identityOf(apk))
        apk.appendText("changed")

        assertNull(DexCacheStore(Feature, cacheDir).read(apk, identityOf(apk)))
    }

    @Test fun `rejects a different install path with identical size and mtime`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"), identityOf(apk))
        val copy = File(apk.parentFile, "copy-" + System.nanoTime() + ".apk")
        apk.copyTo(copy)
        copy.setLastModified(apk.lastModified())

        assertNull(DexCacheStore(Feature, cacheDir).read(copy, identityOf(copy)))
    }

    @Test fun `atomic replacement leaves complete values and no temporary file`() {
        val apk = apk()
        val identity = identityOf(apk)
        val store = DexCacheStore(Feature, cacheDir)
        store.write(apk, mapOf("removed" to "old"), identity)
        store.write(apk, mapOf("new" to "complete"), identity)
        assertEquals(mapOf("new" to "complete"), store.read(apk, identity))
        assertEquals(emptyList<String>(), cacheDir.listFiles()!!.filter { it.extension == "tmp" }.map { it.name })
    }

    @Test
    fun `rejects a different feature schema`() {
        val apk = apk()
        val identity = identityOf(apk)
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"), identity)

        assertNull(DexCacheStore(NewerFeature, cacheDir).read(apk, identity))
    }

    @Test
    fun `rejects a corrupt cache file`() {
        val apk = apk()
        val file = File(cacheDir, "hypertweak_dex_test.feature.properties")
        file.writeText("not a properties file\u0000")

        assertNull(DexCacheStore(Feature, cacheDir).read(apk, identityOf(apk)))
    }

    @Test
    fun `read without a cache file returns null`() {
        val apk = apk()
        assertNull(DexCacheStore(Feature, cacheDir).read(apk, identityOf(apk)))
    }

    private fun formatTwoFile(apk: File, size: Long, modified: Long) {
        File(cacheDir, "hypertweak_dex_test.feature.properties").writeText(
            listOf(
                "format=2",
                "feature=test.feature",
                "schema=1",
                "apk_path=" + apk.absolutePath,
                "apk_file_size=" + size,
                "apk_last_modified=" + modified,
                "v.target=La;->a()V",
            ).joinToString("\n")
        )
    }

    @Test
    fun `a legacy cache without current full identity is rejected even with matching metadata`() {
        val apk = apk()
        formatTwoFile(apk, apk.length(), apk.lastModified())

        // A rollback to a module build that writes format 2 must not throw away every target.
        val loaded = DexCacheStore(Feature, cacheDir).read(apk, identityOf(apk))
        assertNull(loaded)
    }

    @Test
    fun `a format 2 cache whose apk changed is rejected`() {
        val apk = apk()
        // An in-place replacement keeps the path but not the size; format 2 carries no layered
        // stamps, so this metadata is the only thing standing between the user and stale targets.
        formatTwoFile(apk, apk.length() + 1, apk.lastModified())

        assertNull(DexCacheStore(Feature, cacheDir).read(apk, identityOf(apk)))
    }

    @Test
    fun `a cache file without a format is rejected`() {
        val apk = apk()
        File(cacheDir, "hypertweak_dex_test.feature.properties").writeText(
            listOf(
                "feature=test.feature",
                "schema=1",
                "apk_path=" + apk.absolutePath,
                "v.target=La;->a()V",
            ).joinToString("\n")
        )

        // Without a format the file cannot be validated at all, so it must not be trusted.
        assertNull(DexCacheStore(Feature, cacheDir).read(apk, identityOf(apk)))
    }

    @Test
    fun `write records the writer pid and time for later attribution`() {
        val apk = apk()
        DexCacheStore(Feature, cacheDir).write(apk, mapOf("k" to "v"), identityOf(apk))
        val text = File(cacheDir, "hypertweak_dex_test.feature.properties").readText()

        assertTrue("writer_pid must be recorded", text.contains("writer_pid="))
        assertTrue("written_at must be recorded", text.contains("written_at="))
        assertTrue("format must be 4", text.contains("format=4"))
    }

    @Test
    fun `exists reports a readable cache file`() {
        val apk = apk()
        val store = DexCacheStore(Feature, cacheDir)
        assertTrue(!store.exists())
        store.write(apk, mapOf("k" to "v"), identityOf(apk))
        assertTrue(store.exists())
    }
    @Test fun `same size same mtime byte replacement rejects cached targets`() {
        val apk = apk("before")
        val store = DexCacheStore(Feature, cacheDir)
        store.write(apk, mapOf("k" to "v"), identityOf(apk))
        val modified = apk.lastModified()
        apk.writeText("after!")
        assertTrue(apk.setLastModified(modified))
        assertNull(store.read(apk, identityOf(apk)))
    }

}
